package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagKnowledgeBase;
import com.jd.diviner.ai.rec.odin.demo.app.tools.product.DeepProductSearchTool;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.memory.AiMemoryClient;
import com.jd.diviner.ai.rec.odin.demo.app.tools.user.AiMemoryUserTools;
import com.jd.diviner.ai.rec.odin.demo.app.domain.product.JdProductCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.tools.product.JdSearchProductsTool;
import com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation.ProductComparisonValidator;
import com.jd.diviner.ai.rec.odin.demo.app.tools.product.ProductKnowledgeTool;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillRetryPolicy;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.net.InetSocketAddress;
import java.net.URI;
import com.sun.net.httpserver.HttpServer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class DeepSkillTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SKILL = "shopping-recommendation-deep";
    private final TestEmbedding embedding = new TestEmbedding();
    private JdProductCatalog catalog;
    private RagKnowledgeBase knowledge;
    private DeepProductSearchTool search;
    private ProductKnowledgeTool details;

    private SkillCatalog legacyCatalog(ToolRegistry registry) {
        var resource = new ClassPathResource("skills/recommendation.json") {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                try (var stream = super.getInputStream()) {
                    var root = MAPPER.readTree(stream);
                    for (JsonNode skill : root.path("skills")) {
                        if (SKILL.equals(skill.path("id").asText())) ((ObjectNode) skill).put("executionMode", "AGENT");
                    }
                    return new java.io.ByteArrayInputStream(MAPPER.writeValueAsBytes(root));
                }
            }
        };
        return new SkillCatalog(MAPPER, resource, registry);
    }

    @BeforeEach
    void setUp() throws Exception {
        var root = MAPPER.createObjectNode().put("schemaVersion", 1).put("source", "JD_PAGE_SNAPSHOT");
        var products = root.putArray("products");
        for (int index = 0; index < 64; index++) {
            String category = index < 32 ? "手机" : "跑步";
            String id = Long.toString(100000000000L + index);
            var product = products.addObject().put("id", id).put("sourceUrl", "https://item.jd.com/" + id + ".html")
                    .put("name", category + "测试商品" + index).put("category", category).put("description", "用于隔离测试")
                    .put("price", 200).put("currency", "CNY").put("priceType", "PAGE_SNAPSHOT").put("availability", "UNKNOWN")
                    .put("capturedAt", "2026-10-28T08:00:00Z").put("selectedOptions", category + "当前测试规格")
                    .put("specifications", (category + "原文参数仅供此测试商品使用，需核对型号、尺寸、适用场景，不能移用于其他型号。\n\n").repeat(25))
                    .put("services", "页面标注一年质保，具体保修条件需购买前核对，不承诺任何额外服务。");
            product.putArray("tags").add(category);
        }
        ((ObjectNode) products.get(60)).put("availability", "UNAVAILABLE");
        ((ObjectNode) products.get(61)).putNull("price");
        ((ObjectNode) products.get(62)).put("price", 5000);
        ((ObjectNode) products.get(63)).put("capturedAt", "2026-09-01T08:00:00Z");
        catalog = new JdProductCatalog(MAPPER, new ByteArrayResource(MAPPER.writeValueAsBytes(root)),
                Clock.fixed(Instant.parse("2026-10-29T08:00:00Z"), ZoneOffset.UTC), Duration.ofDays(7));
        knowledge = new RagKnowledgeBase(embedding, "test", catalog::documents, 1, 0.5);
        search = new DeepProductSearchTool(MAPPER, catalog, knowledge);
        details = new ProductKnowledgeTool(MAPPER, knowledge, catalog, true);
    }

    private ObjectNode searchArguments(String... interests) {
        var arguments = MAPPER.createObjectNode().put("budget", 800).put("limit", 48);
        arguments.set("interests", MAPPER.valueToTree(interests));
        return arguments;
    }

    private ObjectNode knowledgeArguments(List<String> ids) {
        var arguments = MAPPER.createObjectNode().put("question", "规格、适用场景与服务条件");
        arguments.set("productIds", MAPPER.valueToTree(ids));
        return arguments;
    }

    @Test
    void broadRecallUsesBothPathsAndPreservesDiversityAndEligibility() {
        var result = search.execute(searchArguments("手机", "跑步"));
        assertEquals(48, result.path("products").size());
        assertEquals(60, result.at("/coverage/eligibleProducts").asInt());
        assertEquals(64, result.at("/coverage/indexedProducts").asInt());
        assertEquals(60, result.at("/coverage/matchedProducts").asInt());
        assertEquals(2, result.at("/coverage/semanticQueries").asInt());
        assertNotEquals(result.at("/products/0/category"), result.at("/products/1/category"));
        assertTrue(embedding.calls.get() > 2);
        for (JsonNode product : result.path("products")) {
            assertTrue(product.path("price").asInt() <= 800);
            assertEquals("UNKNOWN", product.path("availability").asText());
            assertEquals("PAGE_SNAPSHOT", product.path("priceType").asText());
            assertFalse(product.has("specifications"));
        }
        assertThrows(IllegalArgumentException.class, () -> new JdSearchProductsTool(MAPPER, catalog).execute(searchArguments("手机")));
        assertThrows(IllegalArgumentException.class, () -> search.execute(searchArguments("手机").put("limit", 49)));
    }

    @Test
    void onlyIndexedProductsAreReturnedAndEmptyInterestDoesNotQueryModel() {
        knowledge = new RagKnowledgeBase(embedding, "test", () -> Arrays.copyOf(catalog.documents(), 5), 1, 0.5);
        search = new DeepProductSearchTool(MAPPER, catalog, knowledge);
        var result = search.execute(searchArguments("手机"));
        assertEquals(5, result.path("products").size());
        int calls = embedding.calls.get();
        assertTrue(search.execute(searchArguments()).path("products").isEmpty());
        assertEquals(calls, embedding.calls.get());
        var semantic = search.execute(searchArguments("智能通讯"));
        assertEquals(5, semantic.path("products").size());
    }

    @Test
    void expandsAllChunksOfEightProductsWithoutReembeddingOrLosingVersions() {
        var candidates = search.execute(searchArguments("手机"));
        List<String> ids = new ArrayList<>();
        candidates.path("products").forEach(product -> { if (ids.size() < 8) ids.add(product.path("id").asText()); });
        int calls = embedding.calls.get();
        JsonNode result = details.execute(knowledgeArguments(ids));
        assertEquals(calls, embedding.calls.get());
        assertEquals(8, result.path("products").size());
        for (JsonNode product : result.path("products")) {
            int chunks = knowledge.status().documents().stream().filter(document -> document.source().equals(product.path("productId").asText() + ".md"))
                    .findFirst().orElseThrow().chunks();
            assertTrue(chunks > 1);
            assertEquals(chunks, product.path("evidence").size());
            assertEquals("ALL_INDEXED_CHUNKS", product.path("coverage").asText());
            assertEquals(knowledge.status().version(), product.path("version").asText());
            product.path("evidence").forEach(evidence -> {
                assertEquals(product.path("productId"), evidence.path("productId"));
                assertEquals(product.path("version"), evidence.path("version"));
                assertTrue(evidence.path("sourceUrl").asText().contains(product.path("productId").asText()));
                assertTrue(evidence.path("id").asText().matches("K-" + product.path("productId").asText() + "-\\d+"));
                assertFalse(evidence.path("chunkId").asText().isBlank());
                assertTrue(evidence.path("quoteOptions").isArray());
                assertTrue(evidence.path("quoteOptions").size() <= 3);
                evidence.path("quoteOptions").forEach(quote -> {
                    assertTrue(quote.asText().length() >= 6 && quote.asText().length() <= 500);
                    assertTrue(evidence.path("text").asText().contains(quote.asText()));
                });
            });
            var evidenceIds = new java.util.HashSet<String>();
            product.path("evidence").forEach(evidence -> assertTrue(evidenceIds.add(evidence.path("id").asText())));
        }
        assertThrows(IllegalArgumentException.class, () -> new ProductKnowledgeTool(MAPPER, knowledge, catalog).execute(knowledgeArguments(ids)));
        ids.add(candidates.at("/products/8/id").asText());
        assertThrows(IllegalArgumentException.class, () -> details.execute(knowledgeArguments(ids)));
    }

    @Test
    void validatesSixRecommendationsAndDimensionsButRejectsFabricatedOrCrossSkuQuotes() {
        var ids = List.of("100000000000", "100000000001", "100000000002", "100000000003", "100000000004", "100000000005");
        var retrieved = details.execute(knowledgeArguments(ids));
        Map<String, JsonNode> evidence = new LinkedHashMap<>();
        retrieved.path("products").forEach(product -> evidence.put(product.path("productId").asText(), product));
        ObjectNode output = recommendation(retrieved);
        ProductComparisonValidator.validate(output, evidence, true);
        assertEquals(6, output.path("knowledgeSources").size());
        assertThrows(IllegalArgumentException.class, () -> ProductComparisonValidator.validate(output, evidence));
        var changed = output.deepCopy();
        ((ObjectNode) changed.at("/products/0/comparison/dimensions/0")).put("quote", "不存在的规格或服务不允许编造");
        assertThrows(IllegalArgumentException.class, () -> ProductComparisonValidator.validate(changed, evidence, true));
        var crossSku = output.deepCopy();
        ((ObjectNode) crossSku.at("/products/0/comparison/dimensions/0"))
                .put("evidenceId", output.at("/products/1/comparison/dimensions/0/evidenceId").asText());
        assertThrows(IllegalArgumentException.class, () -> ProductComparisonValidator.validate(crossSku, evidence, true));
    }

    @Test
    void engineLoadsNewSkillAndRunsActualDeepToolsInBothUserModes() throws Exception {
        List<JsonNode> memoryRequests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            memoryRequests.add(request);
            var body = MAPPER.createObjectNode().put("code", 0);
            var results = body.putObject("results");
            for (String type : List.of("long_term_memory", "origin_memory")) {
                results.putArray(type).addObject().put("memory", "喜欢跑步，近期比较手机型号")
                        .put("memory_type", type).put("role", "user");
            }
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var memoryTools = new AiMemoryUserTools(MAPPER, new AiMemoryClient(MAPPER,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search"), Duration.ofSeconds(5), 20));
            exerciseEngine(memoryTools, memoryRequests);
        } finally {
            server.stop(0);
        }
    }

    private void exerciseEngine(AiMemoryUserTools memoryTools, List<JsonNode> memoryRequests) {
        for (String mode : List.of("DEMO", "REAL")) {
            String username = "DEMO".equals(mode) ? "xiaoming" : "315c70c8f4a25681";
            var existing = SkillEngineTests.registry();
            List<SkillTool> tools = new ArrayList<>(existing.describe().stream().map(item -> existing.require(item.get("name"))).toList());
            tools.removeIf(tool -> List.of("get_user_profile", "get_user_behaviors").contains(tool.name()));
            tools.add(memoryTools.profile());
            tools.add(memoryTools.behaviors());
            tools.add(search);
            tools.add(details);
            var registry = new ToolRegistry(tools);
            var skills = legacyCatalog(registry);
            assertTrue(skills.findEnabled(SKILL).supportsUserDataMode());
            var engine = new SkillEngine(MAPPER, skills, registry, new SkillModelClient(prompt -> {
                var responses = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast).toList();
                int round = responses.size();
                if (round == 0) return SkillEngineTests.tool("skill", "Skill", "{\"command\":\"" + SKILL + "\"}");
                if (round == 1) return SkillEngineTests.tool("profile", "get_user_profile", "{\"username\":\"" + username + "\"}");
                if (round == 2) return SkillEngineTests.tool("behavior", "get_user_behaviors", "{\"username\":\"" + username + "\"}");
                if (round == 3) {
                    var arguments = searchArguments("手机", "跑步");
                    if ("REAL".equals(mode)) arguments.putNull("budget");
                    return SkillEngineTests.tool("search", "search_products", arguments.toString());
                }
                try {
                    var result = MAPPER.readTree(responses.get(round - 1).getResponses().get(0).responseData());
                    if (round == 4) {
                        List<String> ids = new ArrayList<>();
                        result.path("products").forEach(product -> { if (ids.size() < 8) ids.add(product.path("id").asText()); });
                        return SkillEngineTests.tool("knowledge", "retrieve_product_knowledge", knowledgeArguments(ids).toString());
                    }
                    return SkillEngineTests.answer(recommendation(result).toString());
                } catch (Exception exception) { throw new AssertionError(exception); }
            }, "test-chat"));
            var run = engine.execute(SKILL, MAPPER.createObjectNode().put("username", username).put("userDataMode", mode));
            assertEquals("SUCCESS", run.status(), run.error());
            assertEquals(6, run.output().path("products").size());
            assertEquals(48, run.output().at("/searchCoverage/returnedProducts").asInt());
            assertEquals(5, run.traces().size());
            assertEquals(6, run.output().path("knowledgeSources").size());
            assertEquals("DEMO".equals(mode) ? 0 : 2, memoryRequests.size());
            memoryRequests.forEach(request -> assertEquals(username, request.path("user_id").asText()));
        }
    }

    @Test
    void repairsCitationsWithExistingEvidenceAndStreamsANewAnswerWithoutRepeatingTools() {
        var result = citationScenario(1, false, true);
        assertEquals("SUCCESS", result.run().status(), result.run().error());
        assertEquals(7, result.run().modelCalls());
        assertEquals(5, result.run().traces().size());
        assertEquals(1, result.events().stream().filter(event -> "MODEL_RETRYING".equals(event.type())).count());
        assertTrue(result.deltas().stream().anyMatch(delta -> delta.modelRound() == 6));
        assertTrue(result.deltas().stream().anyMatch(delta -> delta.modelRound() == 7));
        assertEquals(1, result.run().output().path("knowledgeSources").size());
        assertFalse(result.run().output().toString().contains("不存在于原文的商品参数"));
    }

    @Test
    void stopsAfterTwoCitationRepairsRatherThanPublishingFabricatedClaims() {
        var result = citationScenario(100, false, false);
        assertEquals("FAILED", result.run().status());
        assertEquals(8, result.run().modelCalls());
        assertEquals(5, result.run().traces().size());
        assertEquals(2, result.events().stream().filter(event -> "MODEL_RETRYING".equals(event.type())).count());
        assertTrue(result.run().output().isEmpty());
        assertTrue(result.run().error().contains("QUOTE_NOT_IN_SOURCE"));
    }

    @Test
    void refusesAnyNewToolCallDuringCitationRepair() {
        var result = citationScenario(1, true, false);
        assertEquals("FAILED", result.run().status());
        assertEquals(5, result.run().traces().size());
        assertTrue(result.run().error().contains("拒绝重复执行"));
    }

    private RepairResult citationScenario(int invalidAnswers, boolean extraToolCall, boolean streaming) {
        return citationScenario(invalidAnswers, extraToolCall, streaming, false);
    }

    @Test
    void deepFinalXmlThenCitationMismatchCanBothRecoverWithoutRerunningTools() {
        var result = citationScenario(2, false, true, true);
        assertEquals("SUCCESS", result.run().status(), result.run().error());
        assertEquals(8, result.run().modelCalls());
        assertEquals(5, result.run().traces().size());
        assertEquals(2, result.events().stream().filter(event -> "MODEL_RETRYING".equals(event.type())).count());
        assertEquals(1, result.run().output().path("knowledgeSources").size());
        assertTrue(result.deltas().stream().anyMatch(delta -> delta.modelRound() == 8));
    }

    private RepairResult citationScenario(int invalidAnswers, boolean extraToolCall, boolean streaming, boolean xmlFirst) {
        return citationScenario(invalidAnswers, extraToolCall, streaming, xmlFirst, false);
    }

    @Test
    void fixesMissingFieldsTypesAndScoresTogetherWithoutRepeatingTools() {
        var result = citationScenario(0, false, true, false, true);
        assertEquals("SUCCESS", result.run().status(), result.run().error());
        assertEquals(7, result.run().modelCalls());
        assertEquals(5, result.run().traces().size());
        assertEquals(1, result.events().stream().filter(event -> "MODEL_RETRYING".equals(event.type())).count());
        assertTrue(result.run().output().path("needs").isArray());
        assertFalse(result.run().output().path("summary").asText().isBlank());
        assertEquals(80, result.run().output().at("/products/0/score").asInt());
    }

    private RepairResult citationScenario(int invalidAnswers, boolean extraToolCall, boolean streaming, boolean xmlFirst, boolean shapeFirst) {
        var existing = SkillEngineTests.registry();
        List<SkillTool> tools = new ArrayList<>(existing.describe().stream().map(item -> existing.require(item.get("name"))).toList());
        tools.add(search);
        tools.add(details);
        var registry = new ToolRegistry(tools);
        var skills = legacyCatalog(registry);
        AtomicInteger answers = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                var results = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast).toList();
                boolean finalContext = prompt.getInstructions().get(0).getText().contains("现在处于最终答案生成阶段");
                int count = finalContext ? 5 : results.size();
                if (count == 0) return SkillEngineTests.tool("skill", "Skill", "{\"command\":\"" + SKILL + "\"}");
                if (count == 1) return SkillEngineTests.tool("profile", "get_user_profile", "{\"username\":\"xiaoming\"}");
                if (count == 2) return SkillEngineTests.tool("behaviors", "get_user_behaviors", "{\"username\":\"xiaoming\"}");
                if (count == 3) return SkillEngineTests.tool("search", "search_products", searchArguments("手机").toString());
                if (count == 4) return SkillEngineTests.tool("knowledge", "retrieve_product_knowledge", knowledgeArguments(List.of("100000000000")).toString());
                assertEquals(5, count);
                int answer = answers.incrementAndGet();
                assertEquals("none", ((OpenAiChatOptions) prompt.getOptions()).getToolChoice());
                if (xmlFirst && answer == 1) return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("<tool_call><function=retrieve_product_knowledge>再次检索</function></tool_call>"),
                        ChatGenerationMetadata.builder().finishReason("stop").build())));
                if (!shapeFirst && answer > (xmlFirst ? 2 : 1)) {
                    String correction = prompt.getInstructions().get(prompt.getInstructions().size() - 1).getText();
                    assertTrue(correction.contains("QUOTE_NOT_IN_SOURCE"));
                    assertTrue(correction.contains("不得调用任何工具"));
                    assertTrue(correction.contains("/products/0/comparison/dimensions/0"));
                    if (extraToolCall) return SkillEngineTests.tool("repeat", "search_products", searchArguments("手机").toString());
                }
                try {
                    if (shapeFirst && finalContext) {
                        var context = MAPPER.readTree(prompt.getInstructions().get(1).getText());
                        assertEquals(4, context.path("validationIssues").size());
                        assertTrue(context.has("outputSchema"));
                        assertFalse(context.at("/completedResults/search_products").has("products"));
                    }
                    JsonNode retrieved = finalContext ? MAPPER.createObjectNode().set("products",
                            MAPPER.readTree(prompt.getInstructions().get(1).getText()).path("productKnowledge"))
                            : MAPPER.readTree(results.get(4).getResponses().get(0).responseData());
                    var output = recommendation(retrieved);
                    if (shapeFirst && answer == 1) {
                        output.remove("summary");
                        output.put("needs", "not an array");
                        ((ObjectNode) output.at("/products/0")).put("score", "80%");
                        ((ObjectNode) output.at("/products/0/comparison")).remove("dimensions");
                    }
                    if (answer <= invalidAnswers) ((ObjectNode) output.at("/products/0/comparison/dimensions/0"))
                            .put("quote", "不存在于原文的商品参数");
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(output.toString()),
                            ChatGenerationMetadata.builder().finishReason("stop").build())));
                } catch (Exception exception) { throw new AssertionError(exception); }
            }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
        };
        var engine = new SkillEngine(MAPPER, skills, registry, new SkillModelClient(model, "test", Duration.ofSeconds(5),
                Duration.ofSeconds(10), Duration.ofSeconds(1), SkillRetryPolicy.disabled(), Duration.ofSeconds(60), false));
        var events = new CopyOnWriteArrayList<SkillEngine.Progress>();
        var deltas = new CopyOnWriteArrayList<SkillEngine.ContentDelta>();
        var input = MAPPER.createObjectNode().put("username", "xiaoming").put("userDataMode", "DEMO");
        var run = streaming ? engine.executeStreaming(SKILL, input, events::add, deltas::add, () -> false)
                : engine.execute(SKILL, input, events::add, () -> false);
        return new RepairResult(run, events, deltas);
    }

    private record RepairResult(SkillEngine.Run run, List<SkillEngine.Progress> events, List<SkillEngine.ContentDelta> deltas) {}

    @Test
    void sevenOrEightProductsAreReselectedByModelWithUpdatedSummaryAndNoRepeatedTools() {
        for (int count : new int[] {7, 8}) {
            var result = productLimitScenario(count, false, false, false);
            assertEquals("SUCCESS", result.run().status(), result.run().error());
            assertEquals(7, result.run().modelCalls());
            assertEquals(5, result.run().traces().size());
            assertEquals(6, result.run().output().path("products").size());
            assertEquals("100000000002", result.run().output().at("/products/0/productId").asText());
            assertEquals("已重新优选六件商品", result.run().output().path("comparisonSummary").asText());
            assertEquals(6, result.run().output().path("knowledgeSources").size());
            assertEquals(1, result.events().stream().filter(event -> "MODEL_RETRYING".equals(event.type())).count());
            assertTrue(result.events().stream().anyMatch(event -> event.message().contains("上限6项")));
            assertTrue(result.deltas().stream().anyMatch(delta -> delta.modelRound() == 7));
        }
    }

    @Test
    void persistentProductOverflowStopsAfterTwoRepairsWithoutTruncatingResult() {
        var result = productLimitScenario(8, true, false, false);
        assertEquals("FAILED", result.run().status());
        assertEquals(8, result.run().modelCalls());
        assertEquals(5, result.run().traces().size());
        assertTrue(result.run().output().isEmpty());
        assertTrue(result.run().error().contains("COMPARISON_CONSTRAINT_INVALID"));
    }

    @Test
    void countRepairCannotInvokeToolsOrBypassProductIdentityChecks() {
        var replay = productLimitScenario(8, false, true, false);
        assertEquals("FAILED", replay.run().status());
        assertEquals(5, replay.run().traces().size());
        assertTrue(replay.run().error().contains("拒绝重复执行"));
        var invented = productLimitScenario(8, false, false, true);
        assertEquals("FAILED", invented.run().status());
        assertEquals(6, invented.run().modelCalls());
        assertTrue(invented.events().stream().noneMatch(event -> "MODEL_RETRYING".equals(event.type())));
        assertTrue(invented.run().output().isEmpty());
    }

    private RepairResult productLimitScenario(int count, boolean persistent, boolean replay, boolean invented) {
        var existing = SkillEngineTests.registry();
        List<SkillTool> tools = new ArrayList<>(existing.describe().stream().map(item -> existing.require(item.get("name"))).toList());
        tools.add(search);
        tools.add(details);
        var registry = new ToolRegistry(tools);
        var skills = legacyCatalog(registry);
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                boolean repair = prompt.getInstructions().get(0).getText().contains("现在处于最终答案生成阶段");
                var results = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast).toList();
                if (!repair) {
                    switch (results.size()) {
                        case 0: return SkillEngineTests.tool("skill", "Skill", "{\"command\":\"" + SKILL + "\"}");
                        case 1: return SkillEngineTests.tool("profile", "get_user_profile", "{\"username\":\"xiaoming\"}");
                        case 2: return SkillEngineTests.tool("behaviors", "get_user_behaviors", "{\"username\":\"xiaoming\"}");
                        case 3: return SkillEngineTests.tool("search", "search_products", searchArguments("手机").toString());
                        case 4: return SkillEngineTests.tool("knowledge", "retrieve_product_knowledge", knowledgeArguments(
                                java.util.stream.LongStream.range(100000000000L, 100000000008L).mapToObj(Long::toString).toList()).toString());
                        default: break;
                    }
                }
                assertEquals("none", ((OpenAiChatOptions) prompt.getOptions()).getToolChoice());
                try {
                    JsonNode retrieved;
                    if (repair) {
                        var context = MAPPER.readTree(prompt.getInstructions().get(1).getText());
                        assertEquals("/products", context.at("/constraintError/path").asText());
                        assertEquals(count, context.at("/constraintError/actualItems").asInt());
                        assertEquals(6, context.at("/constraintError/maxItems").asInt());
                        assertEquals(6, context.at("/outputLimits/maxProducts").asInt());
                        assertFalse(context.path("completedResults").has("retrieve_product_knowledge"));
                        retrieved = MAPPER.createObjectNode().set("products", context.path("productKnowledge"));
                        if (replay) {
                            var repeated = SkillEngineTests.tool("repeat", "retrieve_product_knowledge", knowledgeArguments(List.of("100000000000")).toString());
                            return new ChatResponse(List.of(new Generation(repeated.getResult().getOutput(),
                                    ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
                        }
                    } else {
                        retrieved = MAPPER.readTree(results.get(4).getResponses().get(0).responseData());
                    }
                    ObjectNode output = recommendation(retrieved, 8);
                    var products = output.withArray("products");
                    if (repair && !persistent) {
                        products.remove(0);
                        products.remove(0);
                        output.put("comparisonSummary", "已重新优选六件商品");
                    } else {
                        while (products.size() > count) products.remove(products.size() - 1);
                    }
                    if (invented) ((ObjectNode) products.get(0)).put("productId", "999999999999");
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(output.toString()),
                            ChatGenerationMetadata.builder().finishReason("stop").build())));
                } catch (Exception exception) { throw new AssertionError(exception); }
            }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
        };
        var engine = new SkillEngine(MAPPER, skills, registry, new SkillModelClient(model, "test", Duration.ofSeconds(5),
                Duration.ofSeconds(10), Duration.ofSeconds(1), SkillRetryPolicy.disabled(), Duration.ofSeconds(60), false));
        var events = new CopyOnWriteArrayList<SkillEngine.Progress>();
        var deltas = new CopyOnWriteArrayList<SkillEngine.ContentDelta>();
        var run = engine.executeStreaming(SKILL, MAPPER.createObjectNode().put("username", "xiaoming").put("userDataMode", "DEMO"),
                events::add, deltas::add, () -> false);
        return new RepairResult(run, events, deltas);
    }

    @Test
    void configuredWorkflowUsesThreeModelRoundsAndServerResolvedQuotes() {
        var run = workflowScenario(0);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(3, run.modelCalls());
        assertEquals(5, run.traces().size());
        assertFalse(run.output().at("/products/0/comparison/dimensions/0/quote").asText().isBlank());
        assertEquals("Q1", run.output().at("/products/0/comparison/dimensions/0/quoteId").asText());
        assertEquals(1, run.output().path("knowledgeSources").size());
    }

    @Test
    void workflowRepairsUnknownQuoteSelectionWithoutRepeatingTools() {
        var run = workflowScenario(1);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(4, run.modelCalls());
        assertEquals(5, run.traces().size());
    }

    @Test
    void workflowStillRejectsPersistentlyInvalidQuoteSelections() {
        var run = workflowScenario(10);
        assertEquals("FAILED", run.status());
        assertEquals(5, run.modelCalls());
        assertEquals(5, run.traces().size());
        assertTrue(run.error().contains("QUOTE_CHOICE_NOT_FOUND"));
        assertTrue(run.output().isEmpty());
    }

    @Test
    void workflowPreservesRoundEventsAndToolsAcrossProtocolFormatAndCitationRepairs() {
        var events = new CopyOnWriteArrayList<SkillEngine.Progress>();
        var run = workflowScenario(1, true, events);

        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(6, run.modelCalls());
        assertEquals(List.of("Skill", "get_user_profile", "get_user_behaviors", "search_products", "retrieve_product_knowledge"),
                run.traces().stream().map(SkillEngine.Trace::tool).toList());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), events.stream().filter(event -> "MODEL_STARTED".equals(event.type()))
                .map(SkillEngine.Progress::modelRound).toList());
        assertEquals(List.of(2, 3, 4, 5, 6), events.stream().filter(event -> "MODEL_COMPLETED".equals(event.type()))
                .map(SkillEngine.Progress::modelRound).toList());
        assertEquals(List.of(1, 4, 5), events.stream().filter(event -> "MODEL_RETRYING".equals(event.type()))
                .map(SkillEngine.Progress::modelRound).toList());
        assertEquals(List.of(4, 5, 6), events.stream().filter(event -> "VALIDATION_STARTED".equals(event.type()))
                .map(SkillEngine.Progress::modelRound).toList());
        assertEquals("RUN_COMPLETED", events.get(events.size() - 1).type());
        assertEquals(1, events.stream().filter(event -> "RUN_COMPLETED".equals(event.type())).count());
        assertFalse(events.stream().anyMatch(event -> "RUN_FAILED".equals(event.type())));
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1, events.get(index).sequence());
            assertEquals(run.runId(), events.get(index).runId());
        }
        assertFalse(run.output().at("/products/0/comparison/dimensions/0/quote").asText().isBlank());
    }

    private SkillEngine.Run workflowScenario(int invalidAnswers) {
        return workflowScenario(invalidAnswers, false, new CopyOnWriteArrayList<>());
    }

    private SkillEngine.Run workflowScenario(int invalidAnswers, boolean repairProtocolAndFormat, List<SkillEngine.Progress> events) {
        var existing = SkillEngineTests.registry();
        List<SkillTool> tools = new ArrayList<>(existing.describe().stream().map(item -> existing.require(item.get("name"))).toList());
        tools.add(search);
        tools.add(details);
        var registry = new ToolRegistry(tools);
        var skills = new SkillCatalog(MAPPER, new ClassPathResource("skills/recommendation.json"), registry);
        assertTrue(skills.findEnabled(SKILL).deepWorkflow());
        var answers = new AtomicInteger();
        var invalidDecisionPending = new AtomicBoolean(repairProtocolAndFormat);
        var invalidFormatPending = new AtomicBoolean(repairProtocolAndFormat);
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                try {
                    JsonNode context = MAPPER.readTree(prompt.getInstructions().get(1).getText());
                    String content;
                    if (context.has("availableTools")) {
                        assertEquals(1, context.path("availableTools").size());
                        assertTrue(context.at("/completedResults/get_user_profile").isObject());
                        assertTrue(context.at("/completedResults/get_user_behaviors").isObject());
                        String tool = context.at("/availableTools/0/name").asText();
                        assertTrue(List.of("search_products", "retrieve_product_knowledge").contains(tool));
                        var decision = MAPPER.createObjectNode().put("name", tool);
                        decision.set("arguments", "search_products".equals(tool) ? searchArguments("手机")
                                : knowledgeArguments(List.of("100000000000")));
                        content = invalidDecisionPending.getAndSet(false) ? "<tool_call>invalid decision</tool_call>" : decision.toString();
                    } else {
                        assertEquals("none", ((OpenAiChatOptions) prompt.getOptions()).getToolChoice());
                        assertEquals("FINAL_ANSWER", context.path("phase").asText());
                        assertEquals(1, context.path("candidateProducts").size());
                        assertEquals(1, context.path("productKnowledge").size());
                        assertFalse(context.at("/completedResults/search_products").has("products"));
                        assertFalse(context.at("/productKnowledge/0/evidence/0").has("sourceUrl"));
                        assertTrue(context.at("/outputSchema/properties/products/items/properties/comparison/properties/dimensions/items/properties").has("quoteId"));
                        if (invalidFormatPending.getAndSet(false)) {
                            return new ChatResponse(List.of(new Generation(new AssistantMessage("not a JSON answer"),
                                    ChatGenerationMetadata.builder().finishReason("stop").build())));
                        }
                        var retrieved = MAPPER.createObjectNode().set("products", context.path("productKnowledge"));
                        var output = recommendation(retrieved);
                        JsonNode source = context.at("/productKnowledge/0/evidence/0");
                        var claim = (ObjectNode) output.at("/products/0/comparison/dimensions/0");
                        claim.remove("quote");
                        claim.put("quoteId", answers.incrementAndGet() <= invalidAnswers ? "Q999" : source.at("/quoteChoices/0/id").asText());
                        content = output.toString();
                    }
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(content),
                            ChatGenerationMetadata.builder().finishReason("stop").build())));
                } catch (java.io.IOException exception) { throw new AssertionError(exception); }
            }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
        };
        var engine = new SkillEngine(MAPPER, skills, registry, new SkillModelClient(model, "test-model"));
        return engine.executeStreaming(SKILL, MAPPER.createObjectNode().put("username", "xiaoming").put("userDataMode", "DEMO"),
                events::add, delta -> {}, () -> false);
    }

    private ObjectNode recommendation(JsonNode retrieved) {
        return recommendation(retrieved, 6);
    }

    private ObjectNode recommendation(JsonNode retrieved, int maximum) {
        var output = MAPPER.createObjectNode().put("summary", "依据页面规格推荐，价格库存需再次核实")
                .put("comparisonSummary", "仅比较当前选定规格，不能作跨品类性能排名");
        output.putArray("needs");
        var products = output.putArray("products");
        for (JsonNode source : retrieved.path("products")) {
            if (products.size() == maximum) break;
            var product = products.addObject().put("productId", source.path("productId").asText()).put("reason", "适配需求的候选").put("score", 80);
            var comparison = product.putObject("comparison").put("status", "PARTIAL_EVIDENCE").put("fit", "需核实具体用途").put("uncertainty", "未取得独立测评");
            comparison.putArray("strengths");
            comparison.putArray("limitations");
            JsonNode evidence = source.path("evidence").get(0);
            comparison.putArray("dimensions").addObject().put("dimension", "规格").put("text", "页面所载规格")
                    .put("evidenceId", evidence.path("id").asText()).put("quote", evidence.path("text").asText().substring(0, 80));
        }
        return output;
    }

    private static class TestEmbedding extends AbstractEmbeddingModel {
        final AtomicInteger calls = new AtomicInteger();
        public float[] embed(Document document) { return embed(document.getText()); }
        public EmbeddingResponse call(EmbeddingRequest request) {
            calls.incrementAndGet();
            List<Embedding> values = new ArrayList<>();
            for (int index = 0; index < request.getInstructions().size(); index++) {
                String text = request.getInstructions().get(index);
                values.add(new Embedding(text.contains("手机") || text.equals("智能通讯") ? new float[] {1, 0} : new float[] {0, 1}, index));
            }
            return new EmbeddingResponse(values);
        }
    }
}
