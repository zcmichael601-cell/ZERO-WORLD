package com.jd.diviner.ai.rec.odin.demo.app.tools.category;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.BrandNameCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.BrandNameCatalog.BrandCandidate;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.Cid2NameCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.jev.OpenJevClient;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.sniffer.SnifferClient;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.sniffer.SnifferClient.SkuCard;
import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Model-callable Diviner tools. recall_cid2 prefers i2i when skus are present. */
public final class SnifferRecallTools {

    private static final String SKIP_BRAND = "不使用品牌";

    private final ObjectMapper mapper;
    private final SnifferClient sniffer;
    private final BrandNameCatalog brands;
    private final OpenJevClient brandModel;
    private final Cid2NameCatalog cid2Names;

    public SnifferRecallTools(ObjectMapper mapper, SnifferClient sniffer) {
        this(mapper, sniffer, null, null, null);
    }

    public SnifferRecallTools(ObjectMapper mapper, SnifferClient sniffer, BrandNameCatalog brands) {
        this(mapper, sniffer, brands, null, null);
    }

    public SnifferRecallTools(ObjectMapper mapper, SnifferClient sniffer, BrandNameCatalog brands, OpenJevClient brandModel) {
        this(mapper, sniffer, brands, brandModel, null);
    }

    public SnifferRecallTools(
            ObjectMapper mapper,
            SnifferClient sniffer,
            BrandNameCatalog brands,
            OpenJevClient brandModel,
            Cid2NameCatalog cid2Names) {
        this.mapper = mapper;
        this.sniffer = sniffer;
        this.brands = brands;
        this.brandModel = brandModel;
        this.cid2Names = cid2Names;
    }

    public SkillTool i2i() {
        return tool(
                "i2i",
                "根据显式传入的 1～10 个 SKU 做相似商品召回。必须提供 skus，不会从类目名或历史热销表隐式生成种子。",
                """
                {"type":"object","properties":{
                  "skus":{"type":"array","minItems":1,"maxItems":10,"uniqueItems":true,
                    "items":{"type":"string","pattern":"^[1-9][0-9]{5,19}$"}}},
                 "required":["skus"],"additionalProperties":false}
                """,
                this::i2i);
    }

    public SkillTool recallCid2() {
        return tool(
                "recall_cid2",
                "只决策召回参数。有 skus 时优先 i2i。没有 skus 时，cid3 按 cb_cid3、品牌按 cb_brand_id 走 8081；"
                        + "都没有则用兴趣文本走 KNN 675。品牌名先按映射表换成品牌 ID，对不上时由小模型在候选里选一个。",
                """
                {"type":"object","properties":{
                  "cid2":{"type":"integer","minimum":1,"maximum":999999999,
                    "description":"无 skus 时必填的二级类目 ID"},
                  "cid3":{"type":"integer","minimum":1,"maximum":999999999,
                    "description":"可选三级类目 ID；只有明确证据时才传"},
                  "brand":{"type":"string","minLength":1,"maxLength":100,
                    "description":"可选。品牌名按映射表换成品牌 ID 后走 cb_brand_id；已经是数字 ID 时直接使用"},
                  "skus":{"type":"array","minItems":1,"maxItems":10,"uniqueItems":true,
                    "items":{"type":"string","pattern":"^[1-9][0-9]{5,19}$"},
                    "description":"有点击种子时优先走 i2i"}},
                 "additionalProperties":false}
                """,
                this::recall);
    }

    private JsonNode i2i(JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || arguments.size() != 1) {
            throw new IllegalArgumentException("需要唯一的 skus 数组");
        }
        return i2iResult(parseSkus(arguments), null, null, null, Set.of());
    }

    private JsonNode recall(JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || arguments.isEmpty()
                || modelFieldCount(arguments) > 4 || hasUnknownField(arguments)) {
            throw new IllegalArgumentException("需要 skus 或正整数 cid2，以及可选的 cid3 和 brand");
        }
        List<String> skus = arguments.has("skus") ? parseSkus(arguments) : List.of();
        Integer cid2 = optionalPositiveInt(arguments, "cid2");
        Integer cid3 = optionalPositiveInt(arguments, "cid3");
        String brand = optionalBrand(arguments);
        Set<String> exposed = parseExposed(arguments);
        String query = optionalText(arguments, "query", 120);
        if (!skus.isEmpty()) {
            return i2iResult(skus, cid2, cid3, brand, exposed);
        }
        if (cid2 == null && query == null) {
            throw new IllegalArgumentException("没有 skus 时必须提供正整数 cid2 或 query");
        }
        int resolvedCid2 = cid2 == null ? 1 : cid2;
        // 品牌名只进 discoveryInterest，交给 675。数字品牌 ID / cid3 才走 8081。
        String brandId = query != null ? null : SnifferClient.numericBrand(brand);
        String interest = query != null ? query : interestText(arguments, brand);
        ObjectNode result = products("DIVINER_CID2",
                sniffer.recallCid2(resolvedCid2, cid3, brandId, exposed, interest));
        cid2 = resolvedCid2;
        result.put("cid2", cid2);
        putConstraints(result, cid2, cid3, brand);
        ArrayNode skuIds = result.putArray("skuIds");
        result.path("products").forEach(product -> skuIds.add(product.path("skuId").asText()));
        return result;
    }

    private ObjectNode i2iResult(List<String> skus, Integer cid2, Integer cid3, String brand, Set<String> exposed) {
        ObjectNode result = products("DIVINER_I2I", sniffer.i2i(skus, exposed));
        ArrayNode seeds = result.putArray("seedSkuIds");
        skus.forEach(seeds::add);
        if (cid2 != null) {
            result.put("cid2", cid2);
        }
        putConstraints(result, cid2, cid3, brand);
        return result;
    }

    private static void putConstraints(ObjectNode result, Integer cid2, Integer cid3, String brand) {
        if (cid2 == null && cid3 == null && brand == null) {
            return;
        }
        ObjectNode constraints = result.putObject("constraints");
        if (cid2 != null) {
            constraints.put("cid2", cid2);
        }
        if (cid3 != null) {
            constraints.put("cid3", cid3);
        }
        if (brand != null) {
            constraints.put("brand", brand);
        }
    }

    private static Integer optionalPositiveInt(JsonNode arguments, String field) {
        if (!arguments.has(field)) {
            return null;
        }
        if (!arguments.path(field).isIntegralNumber()
                || !arguments.path(field).canConvertToInt()
                || arguments.path(field).asInt() <= 0) {
            throw new IllegalArgumentException(field + " 必须为正整数");
        }
        return arguments.path(field).asInt();
    }

    private ResolvedBrand resolveBrand(String brand) {
        if (brand == null) {
            return new ResolvedBrand(null, false);
        }
        if (brands != null) {
            var mapped = brands.codeOf(brand);
            if (mapped.isPresent()) {
                return new ResolvedBrand(mapped.get(), false);
            }
            String chosen = decideBrand(brand);
            return new ResolvedBrand(chosen, chosen != null);
        }
        String text = brand.strip();
        return new ResolvedBrand(text.matches("[1-9]\\d{0,18}") ? text : null, false);
    }

    /** 映射表没有唯一 ID 时，让小模型在候选里选；选不出或调用失败就不带品牌。 */
    private String decideBrand(String brand) {
        if (brandModel == null) {
            return null;
        }
        List<BrandCandidate> options = brands.candidates(brand, 8);
        if (options.isEmpty()) {
            return null;
        }
        Map<String, String> codeByLabel = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>();
        for (BrandCandidate option : options) {
            String label = optionLabel(option);
            if (codeByLabel.putIfAbsent(label, option.code()) == null) {
                labels.add(label);
            }
        }
        labels.add(SKIP_BRAND);
        try {
            String choice = brandModel.choice(
                    "品牌原文：" + brand,
                    "brand",
                    "映射表对不上这个品牌。选出与原文是同一个品牌的一项；没有把握就选不使用品牌",
                    labels).choice();
            String code = codeByLabel.get(choice);
            ExecutionLog.current().info("BRAND_MODEL_CHOICE", "channel", "SKILL",
                    "brand", brand, "choice", choice, "brandId", code == null ? "" : code);
            return code;
        } catch (RuntimeException exception) {
            ExecutionLog.current().info("BRAND_MODEL_CHOICE_FAILED", "channel", "SKILL",
                    "brand", brand, "error", exception.getClass().getSimpleName());
            return null;
        }
    }

    private static String optionLabel(BrandCandidate candidate) {
        String code = candidate.code();
        String name = candidate.name() == null ? "" : candidate.name().strip();
        String label = name.isEmpty() ? code : code + " " + name;
        if (label.length() <= 100) {
            return label;
        }
        int room = 99 - code.length();
        return room <= 0 ? code : code + " " + name.substring(0, room);
    }

    private record ResolvedBrand(String id, boolean byModel) {
    }

    private static String optionalBrand(JsonNode arguments) {
        if (!arguments.has("brand")) {
            return null;
        }
        if (!arguments.path("brand").isTextual()) {
            throw new IllegalArgumentException("brand 必须是字符串");
        }
        String brand = arguments.path("brand").asText().strip();
        if (brand.isEmpty() || brand.length() > 100
                || brand.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("brand 必须为 1～100 个可见字符");
        }
        return brand;
    }

    private static int modelFieldCount(JsonNode arguments) {
        int count = arguments.size();
        if (arguments.has("exposedSkuIds")) {
            count--;
        }
        if (arguments.has("cid2_name")) {
            count--;
        }
        if (arguments.has("pws")) {
            count--;
        }
        if (arguments.has("query")) {
            count--;
        }
        return count;
    }

    private static boolean hasUnknownField(JsonNode arguments) {
        var fields = arguments.fieldNames();
        while (fields.hasNext()) {
            if (!Set.of("cid2", "cid3", "brand", "skus", "exposedSkuIds", "cid2_name", "pws", "query")
                    .contains(fields.next())) {
                return true;
            }
        }
        return false;
    }

    private static String interestText(JsonNode arguments, String brand) {
        List<String> parts = new ArrayList<>();
        String cid2Name = optionalText(arguments, "cid2_name", 40);
        if (cid2Name != null) {
            parts.add(cid2Name);
        }
        if (brand != null && !brand.isBlank()) {
            parts.add(brand.strip());
        }
        String pws = optionalText(arguments, "pws", 80);
        if (pws != null) {
            parts.add(pws);
        }
        return parts.isEmpty() ? null : String.join("|", parts);
    }

    private static String optionalText(JsonNode arguments, String field, int maxLength) {
        if (!arguments.has(field)) {
            return null;
        }
        if (!arguments.path(field).isTextual()) {
            throw new IllegalArgumentException(field + " 必须是字符串");
        }
        String value = arguments.path(field).asText().strip();
        if (value.isEmpty() || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " 必须为 1～" + maxLength + " 个可见字符");
        }
        return value;
    }

    private static Set<String> parseExposed(JsonNode arguments) {
        if (!arguments.has("exposedSkuIds")) {
            return Set.of();
        }
        JsonNode values = arguments.path("exposedSkuIds");
        if (!values.isArray() || values.size() > 100) {
            throw new IllegalArgumentException("exposedSkuIds 需为不超过 100 个 SKU");
        }
        Set<String> exposed = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || !value.asText().matches("[1-9][0-9]{5,19}") || !exposed.add(value.asText())) {
                throw new IllegalArgumentException("exposedSkuIds 必须是互不重复的有效 SKU");
            }
        }
        return Set.copyOf(exposed);
    }

    private List<String> parseSkus(JsonNode arguments) {
        JsonNode values = arguments.path("skus");
        if (!values.isArray() || values.isEmpty() || values.size() > SnifferClient.MAX_SEEDS) {
            throw new IllegalArgumentException("skus 需包含 1～" + SnifferClient.MAX_SEEDS + " 个 SKU");
        }
        List<String> skus = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("skus 只能包含字符串 SKU");
            }
            String sku = value.asText();
            if (!sku.matches("[1-9][0-9]{5,19}") || !unique.add(sku)) {
                throw new IllegalArgumentException("skus 必须是互不重复的有效 SKU");
            }
            skus.add(sku);
        }
        return List.copyOf(skus);
    }

    private ObjectNode products(String source, List<SkuCard> cards) {
        ObjectNode result = mapper.createObjectNode().put("source", source);
        ArrayNode products = result.putArray("products");
        for (SkuCard card : cards) {
            ObjectNode product = products.addObject()
                    .put("skuId", card.skuId())
                    .put("name", card.name())
                    .put("brand", card.brand())
                    .put("image", card.image());
            if (card.price() == null) {
                product.putNull("price");
            } else {
                product.put("price", card.price());
            }
            putNullable(product, "cid1", card.cid1());
            putNullable(product, "cid2", card.cid2());
            putNullable(product, "cid3", card.cid3());
            attachCid2Name(product, card.cid2());
        }
        result.put("count", products.size());
        return result;
    }

    private void attachCid2Name(ObjectNode product, Integer cid2) {
        if (cid2Names == null || cid2 == null) {
            return;
        }
        Cid2NameCatalog.Entry entry = cid2Names.findByCid2(cid2);
        if (entry == null) {
            return;
        }
        product.put("cid1Name", entry.cid1Name());
        product.put("cid2Name", entry.cid2Name());
        product.put("cid1_name", entry.cid1Name());
        product.put("cid2_name", entry.cid2Name());
    }

    private static void putNullable(ObjectNode target, String field, Integer value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    private SkillTool tool(
            String name,
            String description,
            String schema,
            Function<JsonNode, JsonNode> operation) {
        return new SkillTool() {
            public String name() { return name; }
            public String description() { return description; }
            public String inputSchema() { return schema; }
            public String dataMode() { return "LIVE"; }
            public JsonNode execute(JsonNode arguments) { return operation.apply(arguments); }
        };
    }
}
