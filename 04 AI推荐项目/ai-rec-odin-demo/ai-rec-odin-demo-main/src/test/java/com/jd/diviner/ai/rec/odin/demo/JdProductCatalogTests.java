package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagKnowledgeBase;
import com.jd.diviner.ai.rec.odin.demo.app.domain.product.JdProductCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.tools.product.JdSearchProductsTool;
import com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation.ProductComparisonValidator;
import com.jd.diviner.ai.rec.odin.demo.app.tools.product.ProductKnowledgeTool;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class JdProductCatalogTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC);

    private JdProductCatalog bundled() {
        return new JdProductCatalog(MAPPER, new ClassPathResource("catalog/jd-products-small.json"), CLOCK, Duration.ofDays(7));
    }

    private ObjectNode data() throws Exception {
        try (var stream = new ClassPathResource("catalog/jd-products-small.json").getInputStream()) {
            return (ObjectNode) MAPPER.readTree(stream);
        }
    }

    private JdProductCatalog from(JsonNode data, Clock clock) throws Exception {
        return new JdProductCatalog(MAPPER, new ByteArrayResource(MAPPER.writeValueAsBytes(data)), clock, Duration.ofDays(7));
    }

    @Test
    void realCatalogHasAlignedNumericSkuDocumentsAndOriginalSources() throws Exception {
        var catalog = productionCatalog();
        assertTrue(catalog.documents().length >= 1000);
        assertTrue(catalog.documents().length <= JdProductCatalog.MAX_PRODUCTS);
        for (var document : catalog.documents()) {
            assertTrue(document.getFilename().matches("[1-9][0-9]{5,19}\\.md"));
            var product = catalog.find(document.getFilename().replace(".md", ""));
            String text = document.getContentAsString(StandardCharsets.UTF_8);
            assertTrue(text.contains(product.path("sourceUrl").asText()));
            assertTrue(text.contains(product.path("name").asText()));
            assertTrue(text.contains(product.path("capturedAt").asText()));
            assertFalse(text.contains("SKU-00"));
            assertFalse(product.has("knowledgeText"));
        }
        ((ObjectNode) catalog.find("100266036816")).put("name", "tampered");
        assertNotEquals("tampered", catalog.find("100266036816").path("name").asText());
    }

    @Test
    void filtersUnavailableBudgetUnknownPriceStaleAndIrrelevantProducts() throws Exception {
        var catalog = bundled();
        assertTrue(catalog.search(List.of("办公"), new BigDecimal("1500"), 12).stream()
                .noneMatch(product -> product.path("id").asText().equals("100017657298")));
        assertEquals(3, catalog.search(List.of("音频"), new BigDecimal("1500"), 12).size());
        assertEquals(2, catalog.search(List.of("音频"), new BigDecimal("1498.99"), 12).size());
        assertTrue(catalog.search(List.of("咖啡"), new BigDecimal("600"), 12).isEmpty());
        assertTrue(catalog.search(List.of("家居"), new BigDecimal("273.19"), 12).isEmpty());
        assertEquals(1, catalog.search(List.of("家居"), new BigDecimal("273.20"), 12).size());
        var data = data();
        data.path("products").forEach(product -> ((ObjectNode) product).putNull("price"));
        assertTrue(from(data, CLOCK).search(List.of("音频"), new BigDecimal("9999"), 12).isEmpty());
        assertTrue(from(data(), Clock.offset(CLOCK, Duration.ofDays(8))).search(List.of("音频"), new BigDecimal("9999"), 12).isEmpty());
    }

    @Test
    void catalogRejectsBadSourceDuplicateFutureDateAndOversize() throws Exception {
        for (String field : List.of("sourceUrl", "id", "capturedAt", "availability", "priceType")) {
            var data = data();
            ((ObjectNode) data.path("products").get(0)).put(field, field.equals("capturedAt") ? "2099-01-01T00:00:00Z" : "invalid");
            assertThrows(RuntimeException.class, () -> from(data, CLOCK));
        }
        var data = data();
        ((com.fasterxml.jackson.databind.node.ArrayNode) data.path("products")).add(data.path("products").get(0));
        assertThrows(IllegalArgumentException.class, () -> from(data, CLOCK));
        assertThrows(IllegalArgumentException.class, () -> new JdProductCatalog(MAPPER,
                new ByteArrayResource(new byte[JdProductCatalog.MAX_CATALOG_BYTES + 1]), CLOCK, Duration.ofDays(7)));
        assertThrows(IllegalArgumentException.class, () -> new JdProductCatalog(MAPPER,
                new ClassPathResource("catalog/jd-products.json"), CLOCK, Duration.ZERO));
    }

    @Test
    void candidateToolReportsSnapshotBoundaryAndValidatesArguments() throws Exception {
        var tool = new JdSearchProductsTool(MAPPER, bundled());
        var result = tool.execute(MAPPER.readTree("{\"interests\":[\"音频\"],\"budget\":1500,\"limit\":2}"));
        assertEquals("JD_PAGE_SNAPSHOT", result.path("source").asText());
        assertEquals(2, result.path("products").size());
        assertFalse(result.toString().contains("SKU-00"));
        assertTrue(tool.execute(MAPPER.readTree("{\"interests\":[],\"budget\":null,\"limit\":2}")).path("products").isEmpty());
        assertFalse(tool.execute(MAPPER.readTree("{\"interests\":[\"音频\"],\"budget\":null,\"limit\":2}")).path("budgetKnown").asBoolean());
        for (String json : List.of("{}", "{\"interests\":[],\"limit\":2}",
                "{\"interests\":[\"音频\"],\"budget\":0,\"limit\":2}",
                "{\"interests\":[\"音频\"],\"budget\":1500,\"limit\":13}",
                "{\"interests\":[1],\"budget\":1500,\"limit\":1}")) {
            assertThrows(IllegalArgumentException.class, () -> tool.execute(MAPPER.readTree(json)));
        }
    }

    @Test
    void realDocumentsUseVectorPipelineWithSkuFilterAndServerProvenance() {
        var catalog = bundled();
        var embedding = new TestEmbedding();
        var knowledge = new RagKnowledgeBase(embedding, "test", catalog::documents, 8, 0.5);
        var tool = new ProductKnowledgeTool(MAPPER, knowledge, catalog);
        assertFalse(knowledge.status().ready());
        var result = tool.execute(arguments("100266036816")).path("products").get(0);
        assertTrue(knowledge.status().ready());
        assertFalse(result.path("evidence").isEmpty());
        assertTrue(embedding.calls.get() > 0);
        result.path("evidence").forEach(source -> {
            assertEquals("100266036816.md", source.path("source").asText());
            assertTrue(source.path("id").asText().matches("K-100266036816-\\d+"));
            assertFalse(source.path("chunkId").asText().isBlank());
            assertEquals("https://item.jd.com/100266036816.html", source.path("sourceUrl").asText());
            assertEquals(catalog.find("100266036816").path("capturedAt"), source.path("capturedAt"));
        });
        var version = knowledge.status().version();
        tool.execute(arguments("100130785706"));
        assertEquals(version, knowledge.status().version());
        assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments("SKU-001")));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments("19999999999999")));
    }

    @Test
    void partialComparisonPermitsUnknownLimitationsButRejectsInventedOrCrossSkuQuote() {
        var catalog = bundled();
        var knowledge = new RagKnowledgeBase(new TestEmbedding(), "test", catalog::documents, 8, 0.5);
        var retrieved = new ProductKnowledgeTool(MAPPER, knowledge, catalog).execute(arguments("100266036816")).path("products").get(0);
        var result = partial(retrieved, catalog.find("100266036816").path("name").asText());
        ProductComparisonValidator.validate(result, Map.of("100266036816", retrieved));
        assertFalse(result.path("knowledgeSources").isEmpty());
        assertTrue(result.at("/products/0/comparison/limitations").isEmpty());
        ((ObjectNode) result.at("/products/0/comparison/strengths/0")).put("quote", "独立实测续航三十小时");
        assertThrows(IllegalArgumentException.class, () -> ProductComparisonValidator.validate(result, Map.of("100266036816", retrieved)));
        var crossSku = partial(retrieved, catalog.find("100266036816").path("name").asText());
        ((ObjectNode) crossSku.path("products").get(0)).put("productId", "100130785706");
        assertThrows(IllegalArgumentException.class, () -> ProductComparisonValidator.validate(crossSku, Map.of("100130785706", retrieved)));
    }

    @Test
    void configuredSkillExecutesToolsAndRecommendsOnlyRealSkuWithVerifiedQuotes() {
        verifyRealSkuRecommendation(false);
    }

    @Test
    void explicitDemoUsesLocalUserButStillRetrievesRealSkuKnowledgeAndValidatesComparison() {
        verifyRealSkuRecommendation(true);
    }

    private void verifyRealSkuRecommendation(boolean demo) {
        var catalog = bundled();
        var knowledge = new RagKnowledgeBase(new TestEmbedding(), "test", catalog::documents, 8, 0.5);
        var original = SkillEngineTests.registry();
        List<SkillTool> tools = original.describe().stream().map(item -> original.require(item.get("name")))
                .filter(tool -> !List.of("search_products", "retrieve_product_knowledge").contains(tool.name()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (demo) tools.replaceAll(tool -> List.of("get_user_profile", "get_user_behaviors").contains(tool.name())
                ? new SkillTool() {
                    public String name() { return tool.name(); }
                    public String description() { return tool.description(); }
                    public String inputSchema() { return tool.inputSchema(); }
                    public String dataMode() { return "LIVE"; }
                    public JsonNode execute(JsonNode arguments) { throw new AssertionError("演示模式不能调用真实用户服务"); }
                } : tool);
        tools.add(new JdSearchProductsTool(MAPPER, catalog));
        tools.add(new ProductKnowledgeTool(MAPPER, knowledge, catalog));
        var registry = new ToolRegistry(tools);
        var model = new SkillModelClient(prompt -> {
            var responses = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).toList();
            return switch (responses.size()) {
                case 0 -> SkillEngineTests.tool("skill", "Skill", "{\"command\":\"shopping-recommendation\"}");
                case 1 -> SkillEngineTests.tool("profile", "get_user_profile", "{\"username\":\"xiaoyu\"}");
                case 2 -> SkillEngineTests.tool("behavior", "get_user_behaviors", "{\"username\":\"xiaoyu\"}");
                case 3 -> SkillEngineTests.tool("products", "search_products", "{\"interests\":[\"音频\"],\"budget\":1500,\"limit\":8}");
                case 4 -> SkillEngineTests.tool("knowledge", "retrieve_product_knowledge", arguments("100266036816").toString());
                default -> {
                    try {
                        var retrieved = MAPPER.readTree(responses.get(4).getResponses().get(0).responseData()).path("products").get(0);
                        yield SkillEngineTests.answer(partial(retrieved, catalog.find("100266036816").path("name").asText()).toString());
                    } catch (Exception exception) { throw new AssertionError(exception); }
                }
            };
        }, "test-chat");
        var engine = new SkillEngine(MAPPER, new SkillCatalog(MAPPER, new ClassPathResource("skills/recommendation.json"), registry), registry, model);
        var input = MAPPER.createObjectNode().put("username", "xiaoyu");
        if (demo) input.put("userDataMode", "DEMO");
        var run = engine.execute("shopping-recommendation", input);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(5, run.traces().size());
        assertEquals(6, run.modelCalls());
        assertEquals("100266036816", run.output().at("/products/0/product/id").asText());
        assertEquals(359, run.output().at("/products/0/product/price").asInt());
        assertEquals("https://item.jd.com/100266036816.html", run.output().at("/knowledgeSources/0/sourceUrl").asText());
    }

    private JsonNode arguments(String id) {
        return MAPPER.createObjectNode().put("question", "页面标注的功能与使用限制")
                .set("productIds", MAPPER.createArrayNode().add(id));
    }

    private JdProductCatalog productionCatalog() throws Exception {
        var resource = new ClassPathResource("catalog/jd-products.json");
        Instant latest = Instant.EPOCH;
        try (var stream = resource.getInputStream()) {
            for (JsonNode product : MAPPER.readTree(stream).path("products")) {
                Instant captured = Instant.parse(product.path("capturedAt").asText());
                if (captured.isAfter(latest)) latest = captured;
            }
        }
        return new JdProductCatalog(MAPPER, resource, Clock.fixed(latest.plusSeconds(1), ZoneOffset.UTC), Duration.ofDays(7));
    }

    @Test
    void expandedSkuIndexRetrievesEachProductWithoutCrossSkuEvidenceAndCapsCandidates() throws Exception {
        var catalog = productionCatalog();
        var embedding = new TestEmbedding();
        var knowledge = new RagKnowledgeBase(embedding, "test", catalog::documents, 4, 0.5);
        var status = knowledge.rebuild();
        assertTrue(status.documents().size() >= 1000);
        assertTrue(status.chunks() > 128);
        assertEquals(catalog.documents().length, status.documents().size());
        for (var document : status.documents()) {
            var result = knowledge.retrieve(document.title(), document.source());
            assertFalse(result.sources().isEmpty(), document.source());
            assertTrue(result.sources().stream().allMatch(source -> document.source().equals(source.source())));
        }
        for (String interest : List.of("音频", "办公", "户外", "跑步", "咖啡", "厨房", "家居")) {
            var products = catalog.search(List.of(interest), new BigDecimal("1500"), 8);
            assertFalse(products.isEmpty(), interest);
            assertTrue(products.size() <= 8);
            assertTrue(products.stream().allMatch(product -> product.path("price").decimalValue().compareTo(new BigDecimal("1500")) <= 0));
        }
    }

    @Test
    void catalogAndIndexRejectExcessCountsBeforeCallingEmbedding() throws Exception {
        var data = data();
        var products = data.putArray("products");
        for (int index = 0; index <= JdProductCatalog.MAX_PRODUCTS; index++) products.addObject();
        assertThrows(IllegalArgumentException.class, () -> from(data, CLOCK));
        var embedding = new TestEmbedding();
        var knowledge = new RagKnowledgeBase(embedding, "test",
                () -> new org.springframework.core.io.Resource[RagKnowledgeBase.MAX_DOCUMENTS + 1], 4, 0.5);
        assertThrows(IllegalArgumentException.class, knowledge::rebuild);
        assertEquals(0, embedding.calls.get());
        assertFalse(knowledge.status().ready());
        var duplicate = bundled().documents()[0];
        var duplicated = new RagKnowledgeBase(embedding, "test",
                () -> new org.springframework.core.io.Resource[] {duplicate, duplicate}, 4, 0.5);
        assertThrows(IllegalArgumentException.class, duplicated::rebuild);
        assertEquals(0, embedding.calls.get());
    }

    @Test
    void mainstreamCategoriesAreSearchableAndUseOnlyTheirOwnSkuEvidence() throws Exception {
        var catalog = productionCatalog();
        var knowledge = new RagKnowledgeBase(new TestEmbedding(), "test", catalog::documents, 4, 0.5);
        var status = knowledge.rebuild();
        assertTrue(status.documents().size() >= 300);
        var search = new JdSearchProductsTool(MAPPER, catalog);
        var productKnowledge = new ProductKnowledgeTool(MAPPER, knowledge, catalog);
        for (String tag : List.of("手机", "笔记本电脑", "平板电脑", "充电宝", "冰箱", "洗衣机", "空调",
                "牛奶", "抽纸", "洗衣液", "大米", "男装", "女装", "羽绒服", "运动裤")) {
            var request = MAPPER.createObjectNode().putNull("budget").put("limit", 6);
            request.putArray("interests").add(tag);
            var found = search.execute(request).path("products");
            assertFalse(found.isEmpty(), tag);
            assertTrue(found.size() <= 6, tag);
            var product = found.get(0);
            String id = product.path("id").asText();
            assertTrue(product.path("price").decimalValue().signum() > 0, id);
            var retrieved = productKnowledge.execute(arguments(id)).at("/products/0/evidence");
            assertFalse(retrieved.isEmpty(), tag);
            for (var evidence : retrieved) {
                assertEquals(id, evidence.path("productId").asText());
                assertEquals(id + ".md", evidence.path("source").asText());
                assertEquals(product.path("sourceUrl"), evidence.path("sourceUrl"));
                assertEquals(product.path("capturedAt"), evidence.path("capturedAt"));
            }
        }
    }

    private ObjectNode partial(JsonNode retrieved, String quote) {
        var evidence = java.util.stream.StreamSupport.stream(retrieved.path("evidence").spliterator(), false)
                .filter(source -> source.path("text").asText().contains(quote)).findFirst().orElseThrow();
        var result = MAPPER.createObjectNode().put("summary", "根据页面标注推荐，价格和库存以京东商详为准")
                .put("comparisonSummary", "只有页面用途证据，未取得独立测评和完整参数");
        result.putArray("needs").addObject().put("category", "音频").put("title", "降噪耳机选购")
                .put("confidence", 85).put("evidence", "近期浏览降噪耳机").put("priority", "近期优先");
        var product = result.putArray("products").addObject().put("productId", "100266036816")
                .put("score", 85).put("reason", "页面标注降噪，适配近期耳机浏览需求；实际体验需核实");
        var comparison = product.putObject("comparison").put("status", "PARTIAL_EVIDENCE")
                .put("fit", "对音频需求的适配判断").put("uncertainty", "暂无实测参数或可证实限制");
        comparison.putArray("strengths").addObject().put("text", "页面标注降噪耳机").put("quote", quote).put("evidenceId", evidence.path("id").asText());
        comparison.putArray("limitations");
        return result;
    }

    private static class TestEmbedding extends AbstractEmbeddingModel {
        private final AtomicInteger calls = new AtomicInteger();
        public float[] embed(Document document) { return embed(document.getText()); }
        public EmbeddingResponse call(EmbeddingRequest request) {
            calls.incrementAndGet();
            var embeddings = new ArrayList<Embedding>();
            for (int index = 0; index < request.getInstructions().size(); index++) embeddings.add(new Embedding(new float[] {1, 0}, index));
            return new EmbeddingResponse(embeddings);
        }
    }
}
