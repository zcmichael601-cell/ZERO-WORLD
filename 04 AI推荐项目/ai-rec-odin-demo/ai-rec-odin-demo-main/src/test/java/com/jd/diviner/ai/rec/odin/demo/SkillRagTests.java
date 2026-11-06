package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagKnowledgeBase;
import com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation.ProductComparisonValidator;
import com.jd.diviner.ai.rec.odin.demo.app.tools.product.ProductKnowledgeTool;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.junit.jupiter.api.Assertions.*;

class SkillRagTests {

    @TempDir Path directory;
    private RagKnowledgeBase knowledge;
    private ProductKnowledgeTool tool;
    private TestEmbedding embedding;
    private static final String STRENGTH = "缓震结构适合日常慢跑";
    private static final String LIMITATION = "鞋面没有防水能力";

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(directory.resolve("SKU-001.md"), "# 跑鞋 SKU-001\n\n" + STRENGTH + "，" + LIMITATION
                + "。购买前需要确认尺码和脚型，不适合涉水活动，不能保证每个人穿着舒适。本文只适用于跑鞋，不是其他商品的说明，不提供实时价格和库存信息。");
        Files.writeString(directory.resolve("SKU-002.md"), "# 背包 SKU-002\n\n轻量背包便于短途徒步收纳，但不适合重装露营，也不能浸水。购买前需要确认容量和负重需求，本说明只适用于背包，不能套用到跑鞋，不提供实时价格和库存信息。");
        embedding = new TestEmbedding();
        knowledge = new RagKnowledgeBase(embedding, "test", new PathMatchingResourcePatternResolver(), directory.toUri() + "*.md", 4, 0.5);
        tool = new ProductKnowledgeTool(SkillEngineTests.MAPPER, knowledge);
    }

    @Test
    void lazyBuildUsesVectorsAndFiltersSourcesBeforeRetrieval() throws Exception {
        assertFalse(knowledge.status().ready());
        JsonNode result = tool.execute(arguments("SKU-001", "优点和使用限制"));
        assertTrue(knowledge.status().ready());
        assertTrue(embedding.calls.get() > 0);
        var product = result.path("products").get(0);
        assertEquals("FOUND", product.path("status").asText());
        product.path("evidence").forEach(source -> {
            assertEquals("SKU-001.md", source.path("source").asText());
            assertEquals("SKU-001", source.path("productId").asText());
            assertTrue(source.path("id").asText().matches("K-SKU-001-\\d+"));
            assertFalse(source.path("chunkId").asText().isBlank());
        });
        String version = knowledge.status().version();
        int calls = embedding.calls.get();
        JsonNode repeated = tool.execute(arguments("SKU-001", "优点和使用限制"));
        assertEquals(version, knowledge.status().version());
        assertEquals(calls + 1, embedding.calls.get());
        assertEquals(product.path("evidence"), repeated.path("products").get(0).path("evidence"));
    }

    @Test
    void concurrentFirstUsePublishesOnlyOneSnapshot() throws Exception {
        var first = CompletableFuture.supplyAsync(() -> tool.execute(arguments("SKU-001", "优点")));
        var second = CompletableFuture.supplyAsync(() -> tool.execute(arguments("SKU-002", "限制")));
        assertEquals(first.get().path("products").get(0).path("version"), second.get().path("products").get(0).path("version"));
    }

    @Test
    void noMatchAndMissingProductNeverBorrowOtherProductKnowledge() {
        for (JsonNode arguments : List.of(arguments("SKU-001", "无关问题"), arguments("SKU-999", "优点"))) {
            var product = tool.execute(arguments).path("products").get(0);
            assertEquals("INSUFFICIENT_EVIDENCE", product.path("status").asText());
            assertTrue(product.path("evidence").isEmpty());
        }
    }

    @Test
    void rejectsInvalidArgumentsBeforeCallingEmbedding() throws Exception {
        for (String json : List.of("{}", "{\"productIds\":[\"../SKU-001\"],\"question\":\"优点\"}",
                "{\"productIds\":[\"SKU-001\",\"SKU-001\"],\"question\":\"优点\"}",
                "{\"productIds\":[],\"question\":\" \"}", "{\"productIds\":[],\"question\":\"优点\",\"extra\":true}")) {
            assertThrows(IllegalArgumentException.class, () -> tool.execute(SkillEngineTests.MAPPER.readTree(json)));
        }
        assertEquals(0, embedding.calls.get());
        assertTrue(tool.execute(SkillEngineTests.MAPPER.createObjectNode().put("question", "优点").set("productIds", SkillEngineTests.MAPPER.createArrayNode()))
                .path("products").isEmpty());
        assertFalse(knowledge.status().ready());
    }

    @Test
    void realToolLoopPassesKnowledgeToModelAndReturnsVerifiedComparison() {
        List<SkillEngine.Progress> progress = new ArrayList<>();
        SkillEngine.Run run = engine(prompt -> {
            List<ToolResponseMessage> results = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).toList();
            if (results.size() < 5) return SkillEngineTests.scriptedResponse(prompt);
            try {
                JsonNode retrieved = SkillEngineTests.MAPPER.readTree(results.get(4).getResponses().get(0).responseData());
                assertTrue(retrieved.toString().contains(STRENGTH));
                assertFalse(retrieved.at("/products/0/evidence/0").has("source"));
                assertFalse(retrieved.at("/products/0/evidence/0").has("title"));
                return SkillEngineTests.answer(supported(retrieved.path("products").get(0)).toString());
            } catch (Exception exception) { throw new AssertionError(exception); }
        }).execute("shopping-recommendation", SkillProgressTests.input(), progress::add, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(6, run.modelCalls());
        assertEquals("retrieve_product_knowledge", run.traces().get(4).tool());
        assertEquals("SKU-001.md", run.traces().get(4).output().at("/products/0/evidence/0/source").asText());
        assertEquals("SUPPORTED", run.output().at("/products/0/comparison/status").asText());
        assertEquals("SKU-001.md", run.output().at("/knowledgeSources/0/source").asText());
        assertTrue(progress.stream().anyMatch(event -> "TOOL_COMPLETED".equals(event.type()) && tool.name().equals(event.tool())));
    }

    @Test
    void forgedOrCrossProductCitationsAndQuotesAreRejected() {
        JsonNode retrieved = tool.execute(arguments("SKU-001", "优点")).path("products").get(0);
        for (String field : List.of("evidenceId", "quote")) {
            ObjectNode result = supported(retrieved);
            ((ObjectNode) result.at("/products/0/comparison/strengths/0")).put(field, "invented-evidence");
            assertThrows(IllegalArgumentException.class, () -> ProductComparisonValidator.validate(result, Map.of("SKU-001", retrieved)));
        }
        ObjectNode otherProduct = retrieved.deepCopy();
        ((ObjectNode) otherProduct.path("evidence").get(0)).put("productId", "SKU-002");
        assertThrows(IllegalArgumentException.class, () -> ProductComparisonValidator.validate(supported(retrieved), Map.of("SKU-001", otherProduct)));
        assertThrows(IllegalArgumentException.class, () -> ProductComparisonValidator.validate(supported(retrieved), Map.of()));
    }

    @Test
    void separateKnowledgeCallsKeepEarlierProductEvidence() {
        var run = engine(prompt -> {
            List<ToolResponseMessage> results = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).toList();
            if (results.size() == 3) return SkillEngineTests.tool("search", "search_products",
                    "{\"interests\":[\"跑步\",\"户外\"],\"budget\":800,\"limit\":8}");
            if (results.size() < 5) return SkillEngineTests.scriptedResponse(prompt);
            if (results.size() == 5) return SkillEngineTests.tool("second-knowledge", tool.name(), arguments("SKU-002", "优点和限制").toString());
            try {
                JsonNode earlier = SkillEngineTests.MAPPER.readTree(results.get(4).getResponses().get(0).responseData());
                return SkillEngineTests.answer(supported(earlier.path("products").get(0)).toString());
            } catch (Exception exception) { throw new AssertionError(exception); }
        }).execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(2, run.traces().stream().filter(trace -> tool.name().equals(trace.tool())).count());
        assertEquals("SKU-001.md", run.output().at("/knowledgeSources/0/source").asText());
    }

    @Test
    void bundledProductKnowledgeCoversCatalogWithoutDemoModelConfusion() {
        var bundled = new RagKnowledgeBase(embedding, "test", new PathMatchingResourcePatternResolver(),
                "classpath*:knowledge/products/*.md", 4, 0.5);
        var status = bundled.rebuild();
        assertEquals(12, status.documents().size());
        for (var document : status.documents()) {
            assertTrue(document.source().matches("SKU-[0-9]{3}\\.md"));
            assertTrue(document.title().contains(document.source().replace(".md", "")));
            assertFalse(bundled.retrieve(document.title(), document.source()).sources().isEmpty());
        }
    }

    @Test
    void noEvidenceRequiresExplicitAbstentionAndServerOwnsSources() throws Exception {
        JsonNode retrieved = tool.execute(arguments("SKU-001", "无关问题")).path("products").get(0);
        ObjectNode result = (ObjectNode) SkillEngineTests.MAPPER.readTree(SkillEngineTests.FINAL);
        result.putArray("knowledgeSources").addObject().put("text", "forged source");
        ProductComparisonValidator.validate(result, Map.of("SKU-001", retrieved));
        assertTrue(result.path("knowledgeSources").isEmpty());
        ((ObjectNode) result.at("/products/0/comparison")).put("status", "SUPPORTED");
        assertThrows(IllegalArgumentException.class, () -> ProductComparisonValidator.validate(result, Map.of("SKU-001", retrieved)));
    }

    @Test
    void modelCannotSkipRetrievalOrRetrieveUnsearchedProducts() {
        var skipped = engine(prompt -> prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count() == 4
                ? SkillEngineTests.answer(SkillEngineTests.FINAL) : SkillEngineTests.scriptedResponse(prompt))
                .execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("FAILED", skipped.status());
        var unauthorized = engine(prompt -> prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count() == 4
                ? SkillEngineTests.tool("unknown", tool.name(), arguments("SKU-002", "优点").toString()) : SkillEngineTests.scriptedResponse(prompt))
                .execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("FAILED", unauthorized.status());
        assertEquals(0, embedding.calls.get());
    }

    @Test
    void embeddingFailureStopsSkillWithoutInventedComparison() {
        embedding.fail = true;
        var run = engine(SkillEngineTests::scriptedResponse).execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("FAILED", run.status());
        assertEquals("FAILED", run.traces().get(4).status());
        assertTrue(run.output().isEmpty());
        assertFalse(run.toString().contains("embedding-secret"));
        assertFalse(knowledge.status().ready());
    }

    private ObjectNode supported(JsonNode product) {
        try {
            ObjectNode result = (ObjectNode) SkillEngineTests.MAPPER.readTree(SkillEngineTests.FINAL);
            ObjectNode comparison = (ObjectNode) result.at("/products/0/comparison");
            comparison.put("status", "SUPPORTED");
            String id = product.path("evidence").get(0).path("id").asText();
            comparison.putArray("strengths").addObject().put("text", STRENGTH).put("quote", STRENGTH).put("evidenceId", id);
            comparison.putArray("limitations").addObject().put("text", LIMITATION).put("quote", LIMITATION).put("evidenceId", id);
            return result;
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private JsonNode arguments(String productId, String question) {
        return SkillEngineTests.MAPPER.createObjectNode().put("question", question)
                .set("productIds", SkillEngineTests.MAPPER.createArrayNode().add(productId));
    }

    private SkillEngine engine(ChatModel model) {
        var existing = SkillEngineTests.registry();
        List<SkillTool> tools = existing.describe().stream().map(item -> existing.require(item.get("name")))
                .filter(item -> !item.name().equals(tool.name())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        tools.add(tool);
        var registry = new ToolRegistry(tools);
        return new SkillEngine(SkillEngineTests.MAPPER, new SkillCatalog(SkillEngineTests.MAPPER,
                SkillEngineTests.agentCatalogResource(), registry), registry, new SkillModelClient(model, "test-chat"));
    }

    private static class TestEmbedding extends AbstractEmbeddingModel {
        final AtomicInteger calls = new AtomicInteger();
        boolean fail;
        public float[] embed(Document document) { return embed(document.getText()); }
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (fail) throw new IllegalStateException("embedding-secret");
            calls.incrementAndGet();
            List<Embedding> values = new ArrayList<>();
            for (int index = 0; index < request.getInstructions().size(); index++) {
                values.add(new Embedding(request.getInstructions().get(index).contains("无关") ? new float[] {0, 1} : new float[] {1, 0}, index));
            }
            return new EmbeddingResponse(values);
        }
    }
}
