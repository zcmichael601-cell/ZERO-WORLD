package com.jd.diviner.ai.rec.odin.demo.app.tools.product;

import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.domain.product.JdProductCatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagKnowledgeBase;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProductKnowledgeTool implements SkillTool {

    private final ObjectMapper mapper;
    private final RagKnowledgeBase knowledgeBase;
    private final JdProductCatalog catalog;
    private final boolean deep;

    public ProductKnowledgeTool(ObjectMapper mapper, RagKnowledgeBase knowledgeBase) {
        this(mapper, knowledgeBase, null);
    }

    public ProductKnowledgeTool(ObjectMapper mapper, RagKnowledgeBase knowledgeBase, JdProductCatalog catalog) {
        this(mapper, knowledgeBase, catalog, false);
    }

    public ProductKnowledgeTool(ObjectMapper mapper, RagKnowledgeBase knowledgeBase, JdProductCatalog catalog, boolean deep) {
        this.mapper = mapper;
        this.knowledgeBase = knowledgeBase;
        this.catalog = catalog;
        this.deep = deep;
    }

    public String name() { return deep ? "retrieve_product_knowledge_deep" : "retrieve_product_knowledge"; }

    public String description() {
        if (deep) return "对最多 8 个已召回 SKU 展开同一版索引的全部原文片段，完整提供参数、规格、服务与交易条件；不是独立测评，缺失资料不可当作商品缺点。";
        return "按候选 SKU 检索京东商详快照原文，获取页面标注、规格及适用场景依据。首次自动构建真实向量索引，之后复用。页面宣传不是独立测评，缺失参数不是缺点；无命中必须说明资料不足。";
    }

    public String dataMode() { return "LIVE"; }

    public String inputSchema() {
        return """
                {"type":"object","properties":{
                  "productIds":{"type":"array","items":{"type":"string","pattern":"^(?:[1-9][0-9]{5,19}|SKU-[0-9]{3})$"},"maxItems":4,"uniqueItems":true},
                  "question":{"type":"string","minLength":1,"maxLength":500,"description":"要核实的优缺点、使用限制及用户需求；不要询问实时价格或库存"}},
                 "required":["productIds","question"],"additionalProperties":false}
                """.replace("\"maxItems\":4", "\"maxItems\":" + (deep ? 8 : 4));
    }

    public static List<String> productIds(JsonNode arguments) {
        return productIds(arguments, 4);
    }

    public static List<String> productIds(JsonNode arguments, int maximum) {
        if (arguments == null || !arguments.isObject() || arguments.size() != 2 || !arguments.path("productIds").isArray()
                || arguments.path("productIds").size() > maximum || !arguments.path("question").isTextual()) {
            throw new IllegalArgumentException("商品知识检索参数不完整");
        }
        String question = arguments.path("question").asText();
        if (question.isBlank() || question.length() > 500 || question.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("知识问题必须为 1~500 个字符且不含控制字符");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode productId : arguments.path("productIds")) {
            if (!productId.isTextual() || !productId.asText().matches("(?:[1-9][0-9]{5,19}|SKU-[0-9]{3})") || !unique.add(productId.asText())) {
                throw new IllegalArgumentException("商品编号无效或重复");
            }
        }
        return mapperIds(arguments.path("productIds"));
    }

    private static List<String> mapperIds(JsonNode ids) {
        return java.util.stream.StreamSupport.stream(ids.spliterator(), false).map(JsonNode::asText).toList();
    }

    public JsonNode execute(JsonNode arguments) {
        List<String> ids = productIds(arguments, deep ? 8 : 4);
        if (catalog != null && ids.stream().anyMatch(id -> catalog.find(id) == null)) throw new IllegalArgumentException("商品不在京东目录中");
        ArrayNode products = mapper.createArrayNode();
        if (!ids.isEmpty()) knowledgeBase.ensureReady();
        var expanded = deep && !ids.isEmpty() ? knowledgeBase.sourceEvidence(ids.stream().map(id -> id + ".md").toList()) : null;
        for (String productId : ids) {
            String sourceName = productId + ".md";
            String title = knowledgeBase.status().documents().stream().filter(document -> sourceName.equals(document.source()))
                    .map(RagKnowledgeBase.SourceDocument::title).findFirst().orElse(productId);
            var retrieval = expanded == null ? knowledgeBase.retrieve(title + "：" + arguments.path("question").asText(), sourceName)
                    : new RagKnowledgeBase.Retrieval(expanded.version(), expanded.sources().stream()
                            .filter(source -> sourceName.equals(source.source())).toList());
            var product = products.addObject().put("productId", productId).put("version", retrieval.version())
                    .put("status", retrieval.sources().isEmpty() ? "INSUFFICIENT_EVIDENCE" : "FOUND");
            ArrayNode evidence = product.putArray("evidence");
            if (deep) product.put("coverage", "ALL_INDEXED_CHUNKS").put("indexedChunks", retrieval.sources().size());
            for (var source : retrieval.sources()) {
                var item = evidence.addObject().put("id", "K-" + productId + "-" + source.chunk()).put("productId", productId)
                        .put("source", source.source()).put("title", source.title()).put("chunkId", source.chunkId())
                        .put("version", retrieval.version()).put("text", source.text());
                if (deep) {
                    var quotes = item.putArray("quoteOptions");
                    java.util.Arrays.stream(source.text().split("\\R"))
                            .map(String::strip).filter(line -> line.length() >= 6 && line.length() <= 500)
                            .distinct().limit(3).forEach(quotes::add);
                    var choices = item.putArray("quoteChoices");
                    var texts = new java.util.LinkedHashSet<String>();
                    if (source.text().length() >= 6 && source.text().length() <= 500) texts.add(source.text());
                    java.util.Arrays.stream(source.text().split("\\R"))
                            .map(String::strip).filter(line -> line.length() >= 6 && line.length() <= 500)
                            .forEach(texts::add);
                    texts.stream().limit(12).forEach(text -> {
                        String id = "Q" + (choices.size() + 1);
                        choices.addObject().put("id", id).put("text", text);
                    });
                }
                if (catalog != null) {
                    var original = catalog.find(productId);
                    item.put("sourceUrl", original.path("sourceUrl").asText()).put("capturedAt", original.path("capturedAt").asText())
                            .put("sourceType", "JD_PAGE_SNAPSHOT");
                }
            }
        }
        return mapper.createObjectNode().set("products", products);
    }
}
