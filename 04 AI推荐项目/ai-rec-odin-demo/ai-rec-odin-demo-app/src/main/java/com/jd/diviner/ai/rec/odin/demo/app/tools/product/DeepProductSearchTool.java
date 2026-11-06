package com.jd.diviner.ai.rec.odin.demo.app.tools.product;

import com.jd.diviner.ai.rec.odin.demo.app.domain.product.ProductSearchService;

import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.domain.product.JdProductCatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagKnowledgeBase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class DeepProductSearchTool implements SkillTool {
    private final ObjectMapper mapper;
    private final JdProductCatalog catalog;
    private final RagKnowledgeBase knowledgeBase;

    public DeepProductSearchTool(ObjectMapper mapper, JdProductCatalog catalog, RagKnowledgeBase knowledgeBase) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.knowledgeBase = knowledgeBase;
    }

    public String name() { return "search_products_deep"; }
    public String dataMode() { return "JD_PAGE_SNAPSHOT"; }
    public String description() {
        return "从已建索引的京东商品中融合全目录标题/品类/标签匹配与逐兴趣向量召回，轮流覆盖不同需求，最多返回48个候选。"
                + "排除过期、超预算、价格未知和明确缺货商品。候选不是最终推荐，需进一步检索原文确认适配；不是全站搜索或实时价格。";
    }
    public String inputSchema() {
        return new JdSearchProductsTool(mapper, catalog).inputSchema().replace("\"maximum\":12", "\"maximum\":48");
    }

    public JsonNode execute(JsonNode arguments) {
        long start = System.nanoTime();
        List<String> interests = ProductSearchService.validateArguments(arguments, 48);
        knowledgeBase.ensureReady();
        var status = knowledgeBase.status();
        Set<String> indexed = status.documents().stream().map(RagKnowledgeBase.SourceDocument::source).collect(Collectors.toSet());
        Map<String, JsonNode> eligible = new LinkedHashMap<>();
        catalog.eligibleProducts(arguments.path("budget").isNull() ? null : arguments.path("budget").decimalValue())
                .stream().filter(product -> indexed.contains(product.path("id").asText() + ".md"))
                .forEach(product -> eligible.put(product.path("id").asText(), product));
        List<List<String>> rankings = new ArrayList<>();
        Set<String> matched = new LinkedHashSet<>();
        for (String interest : interests) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("深度商品召回已取消");
            Map<String, Double> scores = new LinkedHashMap<>();
            List<String> lexical = eligible.values().stream().filter(product -> lexicalScore(product, interest) > 0)
                    .sorted(Comparator.<JsonNode>comparingInt(product -> lexicalScore(product, interest)).reversed()
                            .thenComparing(product -> product.path("id").asText()))
                    .map(product -> product.path("id").asText()).toList();
            addRanks(scores, lexical);
            var retrieval = knowledgeBase.discoverProducts(interest);
            if (!status.version().equals(retrieval.version())) throw new IllegalStateException("商品索引在召回期间更新，请重新执行推荐");
            List<String> semantic = retrieval.sources().stream().map(source -> source.source().replaceFirst("\\.md$", ""))
                    .filter(eligible::containsKey).distinct().toList();
            addRanks(scores, semantic);
            List<String> ranking = scores.keySet().stream().sorted(Comparator.<String>comparingDouble(scores::get).reversed()
                    .thenComparing(Comparator.naturalOrder())).toList();
            rankings.add(ranking);
            matched.addAll(ranking);
        }
        Set<String> selected = new LinkedHashSet<>();
        int limit = arguments.path("limit").asInt();
        for (int position = 0; selected.size() < limit; position++) {
            boolean available = false;
            for (List<String> ranking : rankings) {
                if (position < ranking.size()) {
                    available = true;
                    selected.add(ranking.get(position));
                    if (selected.size() == limit) break;
                }
            }
            if (!available) break;
        }
        var result = mapper.createObjectNode().put("source", "JD_PAGE_SNAPSHOT")
                .put("notice", "仅覆盖当前索引与有效商品快照；召回相关度不是购买概率或性能证据。价格为采集参考价，库存与成交价需在京东核实。")
                .put("budgetKnown", !arguments.path("budget").isNull());
        var products = result.putArray("products");
        for (String id : selected) {
            var compact = products.addObject();
            JsonNode product = eligible.get(id);
            for (String field : List.of("id", "name", "description", "icon", "category", "tags", "price", "currency", "priceType", "priceNotice", "availability", "stockNotice", "sourceUrl", "capturedAt")) {
                if (product.has(field)) compact.set(field, product.get(field).deepCopy());
            }
            String options = product.path("selectedOptions").asText();
            compact.put("selectedOptionsPreview", options.substring(0, Math.min(400, options.length())))
                    .put("selectedOptionsTruncated", options.length() > 400);
        }
        result.putObject("coverage").put("indexVersion", status.version()).put("indexedProducts", indexed.size())
                .put("eligibleProducts", eligible.size()).put("matchedProducts", matched.size())
                .put("returnedProducts", selected.size()).put("semanticQueries", interests.size());
        if (selected.isEmpty()) result.put("emptyReason", interests.isEmpty() ? "用户需求依据不足，未生成候选" : "当前索引无满足有效期、预算和相关度条件的候选");
        ExecutionLog.current().info("DEEP_PRODUCT_SEARCH_COMPLETED", "indexedProducts", indexed.size(), "eligibleProducts", eligible.size(),
                "matchedProducts", matched.size(), "returnedProducts", selected.size(), "semanticQueries", interests.size(), "elapsedMs", ExecutionLog.elapsed(start));
        return result;
    }

    private static int lexicalScore(JsonNode product, String interest) {
        String query = interest.toLowerCase(Locale.ROOT);
        int score = 0;
        for (JsonNode tag : product.path("tags")) if (tag.asText().equalsIgnoreCase(interest)) score += 4;
        if (product.path("category").asText().toLowerCase(Locale.ROOT).contains(query)) score += 3;
        if (product.path("name").asText().toLowerCase(Locale.ROOT).contains(query)) score += 2;
        return score;
    }

    private static void addRanks(Map<String, Double> scores, List<String> ranking) {
        for (int index = 0; index < ranking.size(); index++) scores.merge(ranking.get(index), 1.0 / (60 + index + 1), Double::sum);
    }
}
