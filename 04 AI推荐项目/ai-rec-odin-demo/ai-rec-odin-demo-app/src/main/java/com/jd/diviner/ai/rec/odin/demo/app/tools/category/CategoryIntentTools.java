package com.jd.diviner.ai.rec.odin.demo.app.tools.category;

import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.CidTopSkuCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.CidTopSkuCatalog.SkuSale;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.jev.OpenJevClient;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.jev.OpenJevClient.ChoiceAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class CategoryIntentTools {

    private static final double LOW_CONFIDENCE = 0.05;
    private static final double LOW_NOUL = 0.55;
    private static final int MAX_SUMMARY = 2000;
    private static final int MAX_SKUS = CidTopSkuCatalog.MAX_SKUS;

    private final ObjectMapper mapper;
    private final OpenJevClient jev;
    private final CidTopSkuCatalog catalog;

    public CategoryIntentTools(ObjectMapper mapper, OpenJevClient jev, CidTopSkuCatalog catalog) {
        this.mapper = mapper;
        this.jev = jev;
        this.catalog = catalog;
    }

    public SkillTool inferCategory() {
        return tool("infer_category_with_jev",
                "根据兴趣与行为摘要，用 OpenJEV 级联判别 cid1→cid2→cid3；返回类目路径、概率与置信度。低置信仍返回 argmax 并标记 lowConfidence。",
                """
                {"type":"object","properties":{
                  "interestSummary":{"type":"string","minLength":1,"maxLength":2000,"description":"由画像与行为整理的兴趣/意图摘要，不要粘贴整段原始 memories"}},
                 "required":["interestSummary"],"additionalProperties":false}
                """,
                this::infer);
    }

    public SkillTool recallTopn() {
        return tool("recall_category_topn",
                "按 cid1/cid2/cid3 从近30天类目热销表召回 TopN 种子 SKU（最多10个），含销量；找不到类目时返回空列表。",
                """
                {"type":"object","properties":{
                  "cid1_name":{"type":"string","minLength":1,"maxLength":100},
                  "cid2_name":{"type":"string","minLength":1,"maxLength":100},
                  "cid3_name":{"type":"string","minLength":1,"maxLength":100},
                  "limit":{"type":"integer","minimum":1,"maximum":10}},
                 "required":["cid1_name","cid2_name","cid3_name","limit"],"additionalProperties":false}
                """,
                this::recall);
    }

    public SkillTool rankCandidates() {
        return tool("rank_candidates_with_jev",
                "用 OpenJEV 在最多10个候选 SKU 中按用户兴趣与类目路径做相关性排序，返回有序 skuIds 与概率。",
                """
                {"type":"object","properties":{
                  "interestSummary":{"type":"string","minLength":1,"maxLength":2000},
                  "categoryPath":{"type":"string","minLength":1,"maxLength":300,"description":"cid1/cid2/cid3 路径文本"},
                  "skuIds":{"type":"array","items":{"type":"string","minLength":1,"maxLength":20},"minItems":1,"maxItems":10,"uniqueItems":true}},
                 "required":["interestSummary","categoryPath","skuIds"],"additionalProperties":false}
                """,
                this::rank);
    }

    private JsonNode infer(JsonNode arguments) {
        String summary = requireSummary(arguments, 1);
        List<String> cid1Options = catalog.intentCid1Names();
        if (cid1Options.isEmpty()) {
            throw new IllegalStateException("类目表为空，无法判别");
        }
        Ranked cid1 = rankByYesNo(summary, cid1Options);
        List<String> cid2Options = catalog.cid2Names(cid1.name());
        if (cid2Options.isEmpty()) {
            throw new IllegalStateException("cid1 下无可用 cid2: " + cid1.name());
        }
        Ranked cid2 = rankByYesNo(summary + " 已选一级类目: " + cid1.name(), cid2Options);
        List<String> cid3Options = catalog.cid3Names(cid1.name(), cid2.name());
        if (cid3Options.isEmpty()) {
            throw new IllegalStateException("cid2 下无可用 cid3: " + cid2.name());
        }
        Ranked cid3 = rankByYesNo(summary + " 已选类目: " + cid1.name() + " / " + cid2.name(), cid3Options);

        ObjectNode result = mapper.createObjectNode();
        result.put("source", "OPENJEV_LAYA");
        result.put("cid1_name", cid1.name());
        result.put("cid2_name", cid2.name());
        result.put("cid3_name", cid3.name());
        result.put("categoryPath", cid1.name() + "/" + cid2.name() + "/" + cid3.name());
        result.set("cid1", rankedNode(cid1));
        result.set("cid2", rankedNode(cid2));
        result.set("cid3", rankedNode(cid3));
        result.put("notice", "每一级把全部候选一次发给 OpenJEV 做是否判断，再按肯定分从高到低排序；低分需在推荐中说明不确定性。");
        return result;
    }

    private Ranked rankByYesNo(String state, List<String> options) {
        Map<String, Double> scores = jev.yesNo(state, options);
        List<String> ordered = new ArrayList<>(options);
        ordered.sort(Comparator.comparingDouble((String name) -> scores.getOrDefault(name, Double.NEGATIVE_INFINITY)).reversed()
                .thenComparing(name -> name));
        String top = ordered.get(0);
        if (!scores.containsKey(top)) {
            throw new IllegalStateException("OpenJEV 未返回是否分: " + top);
        }
        return new Ranked(top, scores.get(top), scores);
    }

    private ObjectNode rankedNode(Ranked ranked) {
        ObjectNode node = mapper.createObjectNode();
        node.put("choice", ranked.name());
        node.put("noul", ranked.score());
        node.put("lowConfidence", ranked.score() < LOW_NOUL);
        ArrayNode order = node.putArray("ranked");
        ranked.scores().entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(10)
                .forEach(entry -> order.addObject().put("name", entry.getKey()).put("noul", entry.getValue()));
        return node;
    }

    private record Ranked(String name, double score, Map<String, Double> scores) {
    }

    private JsonNode recall(JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || arguments.size() != 4
                || !arguments.path("cid1_name").isTextual() || !arguments.path("cid2_name").isTextual()
                || !arguments.path("cid3_name").isTextual() || !arguments.path("limit").isIntegralNumber()) {
            throw new IllegalArgumentException("需要 cid1_name/cid2_name/cid3_name 与 limit");
        }
        String cid1 = requireName(arguments.path("cid1_name").asText(), "cid1_name");
        String cid2 = requireName(arguments.path("cid2_name").asText(), "cid2_name");
        String cid3 = requireName(arguments.path("cid3_name").asText(), "cid3_name");
        int limit = arguments.path("limit").asInt();
        if (limit < 1 || limit > MAX_SKUS) {
            throw new IllegalArgumentException("limit 必须为 1~" + MAX_SKUS);
        }
        List<SkuSale> skus = catalog.topSkus(cid1, cid2, cid3, limit);
        ObjectNode result = mapper.createObjectNode()
                .put("source", "CID3_TOP_SKU_30D")
                .put("cid1_name", cid1)
                .put("cid2_name", cid2)
                .put("cid3_name", cid3)
                .put("limit", limit)
                .put("maxSkus", MAX_SKUS);
        ArrayNode products = result.putArray("products");
        for (SkuSale sku : skus) {
            products.addObject().put("skuId", sku.skuId()).put("saleQty", sku.saleQty());
        }
        result.put("count", products.size());
        if (products.isEmpty()) {
            result.put("emptyReason", "类目热销表中未找到该 cid 路径或已被过滤");
        } else {
            result.put("notice", "以下为类目热销种子 SKU（最多" + MAX_SKUS + "个），需再用相关性排序，不能直接当作最终个性化推荐。");
        }
        return result;
    }

    private JsonNode rank(JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || arguments.size() != 3) {
            throw new IllegalArgumentException("需要 interestSummary、categoryPath、skuIds");
        }
        String summary = requireSummary(arguments, 3);
        String path = arguments.path("categoryPath").asText();
        if (!arguments.path("categoryPath").isTextual() || path.isBlank() || path.length() > 300
                || path.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("categoryPath 无效");
        }
        List<String> skuIds = requireSkuIds(arguments.path("skuIds"));
        String state = summary + "\n目标类目: " + path + "\n候选SKU: " + String.join(",", skuIds);
        ChoiceAnswer answer = jev.choice(state, "best_sku",
                "criteria 的值是候选 SKU。选出与用户意图和目标类目最相关的一个；只依据摘要，不臆造商品属性",
                skuIds);

        List<String> ordered = new ArrayList<>(skuIds.size());
        ordered.add(answer.choice());
        skuIds.stream()
                .filter(sku -> !sku.equals(answer.choice()))
                .sorted(Comparator.comparingDouble((String sku) -> -answer.probabilities().getOrDefault(sku, 0.0))
                        .thenComparing(sku -> sku))
                .forEach(ordered::add);

        ObjectNode result = mapper.createObjectNode();
        result.put("source", "OPENJEV_LAYA");
        result.put("categoryPath", path);
        result.put("topSkuId", answer.choice());
        result.put("confidence", answer.confidence());
        result.put("lowConfidence", answer.confidence() < LOW_CONFIDENCE);
        result.put("maxSkus", MAX_SKUS);
        ArrayNode orderedNode = result.putArray("orderedSkuIds");
        ordered.forEach(orderedNode::add);
        ObjectNode probs = result.putObject("probabilities");
        answer.probabilities().forEach(probs::put);
        result.put("notice", "排序仅表示相对相关性；本地无商详快照时不得编造参数，可说明证据不足。");
        return result;
    }

    private ObjectNode levelNode(ChoiceAnswer answer) {
        ObjectNode node = mapper.createObjectNode();
        node.put("choice", answer.choice());
        node.put("confidence", answer.confidence());
        node.put("lowConfidence", answer.confidence() < LOW_CONFIDENCE);
        ObjectNode probs = node.putObject("probabilities");
        answer.probabilities().forEach(probs::put);
        return node;
    }

    private String requireSummary(JsonNode arguments, int expectedSize) {
        if (arguments == null || !arguments.isObject() || arguments.size() < expectedSize
                || !arguments.path("interestSummary").isTextual()) {
            throw new IllegalArgumentException("需要 interestSummary");
        }
        String summary = arguments.path("interestSummary").asText()
                .replaceAll("\\p{Cc}+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (summary.isEmpty() || summary.length() > MAX_SUMMARY) {
            throw new IllegalArgumentException("interestSummary 需为 1~" + MAX_SUMMARY + " 字符");
        }
        return summary;
    }

    private static String requireName(String value, String field) {
        String name = value == null ? "" : value.strip();
        if (name.isEmpty() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " 无效");
        }
        return name;
    }

    private static List<String> requireSkuIds(JsonNode node) {
        if (!node.isArray() || node.isEmpty() || node.size() > MAX_SKUS) {
            throw new IllegalArgumentException("skuIds 需为 1~" + MAX_SKUS + " 个");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("skuId 必须为字符串");
            }
            String sku = item.asText().strip();
            if (sku.isEmpty() || sku.length() > 20 || !sku.matches("[1-9][0-9]{5,19}") || !unique.add(sku)) {
                throw new IllegalArgumentException("skuId 无效或重复（最多" + MAX_SKUS + "个）");
            }
        }
        return List.copyOf(unique);
    }

    private SkillTool tool(String name, String description, String schema, Function<JsonNode, JsonNode> operation) {
        return new SkillTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String inputSchema() {
                return schema;
            }

            @Override
            public String dataMode() {
                return "LIVE";
            }

            @Override
            public JsonNode execute(JsonNode arguments) {
                return operation.apply(arguments);
            }
        };
    }
}
