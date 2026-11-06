package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation.FastRecommendationContext;
import com.jd.diviner.ai.rec.odin.demo.app.domain.product.JdProductCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.tools.product.JdSearchProductsTool;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillTimingHistory;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;

class FastSkillTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SKILL = "shopping-recommendation-fast";

    private JdSearchProductsTool search() {
        return new JdSearchProductsTool(MAPPER, new JdProductCatalog(MAPPER,
                new ClassPathResource("catalog/jd-products-small.json"),
                Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC), Duration.ofDays(7)));
    }

    private SkillTool userTool(String name, Function<JsonNode, JsonNode> operation) {
        return new SkillTool() {
            public String name() { return name; }
            public String dataMode() { return "LIVE"; }
            public String description() { return "User data fixture"; }
            public String inputSchema() { return "{\"type\":\"object\"}"; }
            public JsonNode execute(JsonNode arguments) { return operation.apply(arguments); }
        };
    }

    private SkillEngine engine(ChatModel model, Function<JsonNode, JsonNode> users) {
        var original = SkillEngineTests.registry();
        var tools = new ArrayList<SkillTool>(List.of(userTool("get_user_profile", users), userTool("get_user_behaviors", users),
                search(), original.require("retrieve_product_knowledge"), original.require("get_beijing_weather"),
                original.require("get_beijing_hot_attractions")));
        tools.addAll(SkillEngineTests.jevCidTools());
        var registry = new ToolRegistry(tools);
        return new SkillEngine(MAPPER, new SkillCatalog(MAPPER, new ClassPathResource("skills/recommendation.json"), registry),
                registry, new SkillModelClient(model, "test-model"), new SkillTimingHistory(() -> {
                    throw new AssertionError("Fast skill must not inspect or initialize RAG");
                }));
    }

    private JsonNode user(JsonNode arguments) {
        var data = MAPPER.createObjectNode().put("username", arguments.path("username").asText())
                .put("source", "AI_MEMORY").put("dataStatus", "AVAILABLE").putNull("budget");
        data.putArray("memories").addObject().put("memory", "关注音频、办公和降噪耳机");
        return data;
    }

    private JsonNode input(String mode) {
        return MAPPER.createObjectNode().put("username", mode.equals("DEMO") ? "xiaoyu" : "315c70c8f4a25681")
                .put("userDataMode", mode);
    }

    private ChatResponse recommendation(Prompt prompt) {
        try {
            String text = prompt.getInstructions().stream().filter(message -> message instanceof org.springframework.ai.chat.messages.UserMessage)
                    .findFirst().orElseThrow().getText();
            JsonNode facts = MAPPER.readTree(text.substring(text.indexOf('{')));
            var result = MAPPER.createObjectNode().put("summary", "基于办公与音频需求推荐；价格与库存以京东页面为准");
            result.putArray("needs");
            var products = result.putArray("products");
            JsonNode candidates = facts.at("/candidates/products");
            if (!candidates.isEmpty()) products.addObject().put("productId", candidates.get(0).path("id").asText())
                    .put("score", 80).put("reason", "适合用户关注的音频场景");
            return SkillEngineTests.answer(result.toString());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void executesRealToolsThenExactlyOneModelCallWithoutRag() {
        var calls = new AtomicInteger();
        var events = new ArrayList<SkillEngine.Progress>();
        var engine = engine(prompt -> {
            calls.incrementAndGet();
            assertTrue(prompt.getSystemMessage().getText().contains("极速个性化推荐"));
            assertTrue(((org.springframework.ai.openai.OpenAiChatOptions) prompt.getOptions()).getToolCallbacks().isEmpty());
            assertEquals("low", ((org.springframework.ai.openai.OpenAiChatOptions) prompt.getOptions()).getReasoningEffort());
            return recommendation(prompt);
        }, this::user);
        var run = engine.execute(SKILL, input("REAL"), events::add, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(1, calls.get());
        assertEquals(1, run.modelCalls());
        assertEquals(List.of("Skill", "get_user_profile", "get_user_behaviors", "search_products"),
                run.traces().stream().map(SkillEngine.Trace::tool).toList());
        assertTrue(run.traces().stream().allMatch(trace -> trace.modelRound() == 0));
        assertTrue(run.output().path("products").get(0).path("product").has("sourceUrl"));
        assertTrue(run.traces().get(3).arguments().path("budget").isNull());
        assertFalse(run.output().has("comparisonSummary"));
        assertTrue(events.stream().anyMatch(event -> "TOOL_COMPLETED".equals(event.type())));
        assertTrue(engine.estimate(SKILL).stages().stream().noneMatch(stage -> List.of("knowledge", "planning").contains(stage.id())));
    }

    @Test
    void demoUsersBypassMemoryButStillUseJdCatalogAndModel() {
        var engine = engine(this::recommendation, arguments -> { throw new AssertionError("Demo must not call Memory"); });
        var run = engine.execute(SKILL, input("DEMO"));
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals("xiaoyu", run.output().path("profile").path("username").asText());
        assertEquals(1500, run.traces().get(3).arguments().path("budget").asInt());
        assertTrue(run.output().path("products").get(0).path("productId").asText().matches("[0-9]+"));
        assertThrows(IllegalArgumentException.class, () -> engine.execute(SKILL,
                MAPPER.createObjectNode().put("username", "not-a-demo").put("userDataMode", "DEMO")));
    }

    @Test
    void emptyMemoryProducesNoInventedInterestsOrProducts() {
        var run = engine(this::recommendation, arguments -> MAPPER.createObjectNode().put("source", "AI_MEMORY")
                .put("dataStatus", "EMPTY").putNull("budget").set("memories", MAPPER.createArrayNode()))
                .execute(SKILL, input("REAL"));
        assertEquals("SUCCESS", run.status(), run.error());
        assertTrue(run.traces().get(3).arguments().path("interests").isEmpty());
        assertTrue(run.output().path("products").isEmpty());
        assertEquals("INSUFFICIENT_USER_INFORMATION", run.output().path("selectionStatus").asText());
        assertEquals(1, run.modelCalls());
        assertTrue(run.output().path("summary").asText().contains("用户资料不足"));
        assertEquals(0, run.output().path("candidateCount").asInt());
    }

    @Test
    void unmatchedInterestsAreReportedAsEmptyRetrieval() {
        var run = engine(this::recommendation, arguments -> {
            var data = (com.fasterxml.jackson.databind.node.ObjectNode) user(arguments);
            data.putArray("memories").addObject().put("memory", "希望购买天文观测设备");
            return data;
        }).execute(SKILL, input("REAL"));
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals("NO_CANDIDATES", run.output().path("selectionStatus").asText());
        assertEquals(1, run.modelCalls());
        assertEquals(0, run.output().path("candidateCount").asInt());
        assertTrue(run.output().path("products").isEmpty());
    }

    @Test
    void finalContextPreservesCandidateCountSearchArgumentsAndRetrievalTags() {
        var run = engine(prompt -> {
            try {
                String text = prompt.getUserMessage().getText();
                JsonNode facts = MAPPER.readTree(text.substring(text.indexOf('{')));
                JsonNode products = facts.at("/candidates/products");
                assertFalse(products.isEmpty());
                assertEquals(products.size(), facts.path("candidateCount").asInt());
                var productSchema = facts.at("/outputSchema/properties/products");
                assertEquals(1, productSchema.path("minItems").asInt());
                assertEquals(2, productSchema.path("maxItems").asInt());
                assertEquals(products.size(), productSchema.at("/items/properties/productId/enum").size());
                assertEquals("integer", productSchema.at("/items/properties/score/type").asText());
                assertEquals(search().prepareArguments(FastRecommendationContext.compact(user(input("REAL"))),
                        FastRecommendationContext.compact(user(input("REAL")))), facts.path("searchArguments"));
                for (JsonNode product : products) {
                    assertFalse(product.path("tags").isEmpty());
                    assertFalse(product.has("knowledgeText"));
                    assertFalse(product.has("specifications"));
                }
                assertTrue(prompt.getSystemMessage().getText().contains("匹配一项有效需求即可"));
                assertTrue(prompt.getSystemMessage().getText().contains("availability=UNKNOWN"));
                return recommendation(prompt);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }, this::user).execute(SKILL, input("REAL"));
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals("RECOMMENDED", run.output().path("selectionStatus").asText());
        assertEquals(1, run.modelCalls());
    }

    @Test
    void emptySelectionIsRepairedByModelUsingSameCandidatesNotCatalogOrder() {
        var calls = new AtomicInteger();
        var prompts = new ArrayList<Prompt>();
        var events = new ArrayList<SkillEngine.Progress>();
        var run = engine(prompt -> {
            prompts.add(prompt);
            if (calls.incrementAndGet() == 1) {
                return SkillEngineTests.answer("{\"summary\":\"暂无匹配商品\",\"needs\":[],\"products\":[]}");
            }
            assertEquals(prompts.get(0).getInstructions(), prompt.getInstructions().subList(0, 2));
            assertTrue(prompt.getInstructions().get(2).getText().contains("唯一一次修正"));
            assertTrue(prompt.getInstructions().get(3).getText().contains("MIN_ITEMS_NOT_MET"));
            assertTrue(prompt.getInstructions().get(3).getText().contains("invalidDraft"));
            try {
                String text = prompt.getInstructions().get(1).getText();
                JsonNode facts = MAPPER.readTree(text.substring(text.indexOf('{')));
                var products = facts.at("/candidates/products");
                assertTrue(products.size() > 1);
                var selected = MAPPER.createObjectNode().put("summary", "已选择；价格与库存以京东页面为准");
                selected.putArray("needs");
                selected.putArray("products").addObject().put("productId", products.get(products.size() - 1).path("id").asText())
                        .put("score", 78).put("reason", "关注办公场景，适配待确认");
                return SkillEngineTests.answer(selected.toString());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }, this::user).execute(SKILL, input("REAL"), events::add, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        var candidates = run.traces().get(3).output().path("products");
        assertEquals(candidates.get(candidates.size() - 1).path("id"), run.output().at("/products/0/productId"));
        assertEquals(78, run.output().at("/products/0/score").asInt());
        assertEquals("关注办公场景，适配待确认", run.output().at("/products/0/reason").asText());
        assertEquals("MODEL", run.output().at("/products/0/selectionSource").asText());
        assertEquals(1, run.output().path("products").size());
        assertEquals(2, run.modelCalls());
        assertEquals(4, run.traces().size());
        assertEquals(1, events.stream().filter(event -> "MODEL_RETRYING".equals(event.type())).count());
    }

    @Test
    void invalidDraftsGetOneRepairAndNeverReplayTools() {
        for (String invalid : List.of(
                "{\"summary\":\"无结果\",\"needs\":[],\"products\":[]}",
                "{\"summary\":\"缺少字段\",\"needs\":[]}",
                "{\"summary\":\"无效商品\",\"needs\":[],\"products\":[{\"productId\":\"fake\",\"reason\":\"test\",\"score\":150}]}",
                "{\"summary\":broken")) {
            var calls = new AtomicInteger();
            var run = engine(prompt -> calls.incrementAndGet() == 1 ? SkillEngineTests.answer(invalid) : recommendation(prompt),
                    arguments -> { throw new AssertionError("Demo must not call Memory"); }).execute(SKILL, input("DEMO"));
            assertEquals("SUCCESS", run.status(), run.error());
            assertEquals("RECOMMENDED", run.output().path("selectionStatus").asText());
            assertFalse(run.output().path("products").isEmpty());
            assertEquals(2, run.modelCalls());
            assertEquals(4, run.traces().size());
        }
    }

    @Test
    void repeatedEmptySelectionFailsWithoutPublishingProgramSelectedProducts() {
        var run = engine(prompt -> SkillEngineTests.answer("{\"summary\":\"暂无匹配商品\",\"needs\":[],\"products\":[]}"),
                this::user).execute(SKILL, input("REAL"));
        assertEquals("FAILED", run.status());
        assertTrue(run.output().isEmpty());
        assertTrue(run.error().contains("未代选商品"));
        assertEquals(2, run.modelCalls());
        assertEquals(4, run.traces().size());
    }

    @Test
    void duplicateSkuAndInvalidScoresAreRepairedNotSilentlyReplaced() {
        for (String problem : List.of("duplicate", "fractionalScore", "missingReason", "tooMany")) {
            var calls = new AtomicInteger();
            var run = engine(prompt -> {
                var valid = recommendation(prompt);
                if (calls.incrementAndGet() > 1) return valid;
                try {
                    var draft = MAPPER.readTree(valid.getResult().getOutput().getText());
                    var products = (com.fasterxml.jackson.databind.node.ArrayNode) draft.path("products");
                    var product = (com.fasterxml.jackson.databind.node.ObjectNode) products.get(0);
                    switch (problem) {
                        case "duplicate" -> products.add(product.deepCopy());
                        case "fractionalScore" -> product.put("score", 80.5);
                        case "missingReason" -> product.remove("reason");
                        case "tooMany" -> { products.add(product.deepCopy()); products.add(product.deepCopy()); }
                        default -> throw new AssertionError(problem);
                    }
                    return SkillEngineTests.answer(draft.toString());
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }, this::user).execute(SKILL, input("REAL"));
            assertEquals("SUCCESS", run.status(), problem + ": " + run.error());
            assertEquals(2, run.modelCalls());
            assertEquals(4, run.traces().size());
            assertEquals(1, run.output().path("products").size());
        }
    }

    @Test
    void singleCandidateModelRepairDoesNotDuplicateSkuAndStillStreams() {
        var chunks = new ArrayList<SkillEngine.ContentDelta>();
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Use streaming"); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                var response = calls.incrementAndGet() == 1
                        ? SkillEngineTests.answer("{\"summary\":\"暂无匹配商品\",\"needs\":[],\"products\":[]}") : recommendation(prompt);
                return Flux.just(new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(response.getResult().getOutput(),
                        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build()))));
            }
        };
        var run = engine(model, arguments -> {
            var data = (com.fasterxml.jackson.databind.node.ObjectNode) user(arguments);
            data.putArray("memories").addObject().put("memory", "关注家居");
            return data;
        }).executeStreaming(SKILL, input("REAL"), event -> {}, chunks::add, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(1, run.output().path("candidateCount").asInt());
        assertEquals(1, run.output().path("products").size());
        assertEquals("RECOMMENDED", run.output().path("selectionStatus").asText());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.modelRound() == 2));
        assertEquals(2, run.modelCalls());
        assertEquals(4, run.traces().size());
    }

    @Test
    void selectedProductsTakePrecedenceOverContradictoryModelSummary() {
        var run = engine(prompt -> {
            var text = recommendation(prompt).getResult().getOutput().getText();
            try {
                var answer = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(text);
                answer.put("summary", "暂无匹配商品").put("emptyReason", "没有商品");
                return SkillEngineTests.answer(answer.toString());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }, this::user).execute(SKILL, input("REAL"));
        assertEquals("SUCCESS", run.status(), run.error());
        assertFalse(run.output().path("products").isEmpty());
        assertEquals("RECOMMENDED", run.output().path("selectionStatus").asText());
        assertFalse(run.output().has("emptyReason"));
        assertFalse(run.output().path("summary").asText().contains("暂无匹配"));
    }

    @Test
    void failuresAndCancellationDoNotFabricateSuccessOrReplayTools() {
        var calls = new AtomicInteger();
        var engine = engine(prompt -> { calls.incrementAndGet(); return recommendation(prompt); }, arguments -> {
            throw new IllegalStateException("Memory unavailable");
        });
        assertEquals("FAILED", engine.execute(SKILL, input("REAL")).status());
        assertEquals(0, calls.get());
        assertThrows(CancellationException.class, () -> engine.execute(SKILL, input("REAL"), event -> {}, () -> true));
    }

    @Test
    void rejectsHallucinatedSkuAndUnexpectedModelToolCall() {
        var wrongSku = engine(prompt -> SkillEngineTests.answer("{\"summary\":\"test\",\"needs\":[],\"products\":[{\"productId\":\"fake\",\"reason\":\"test\",\"score\":90}]}"), this::user);
        assertEquals("FAILED", wrongSku.execute(SKILL, input("REAL")).status());
        var toolRequest = engine(prompt -> SkillEngineTests.tool("unexpected", "retrieve_product_knowledge", "{}"), this::user);
        var run = toolRequest.execute(SKILL, input("REAL"));
        assertEquals("FAILED", run.status());
        assertEquals(4, run.traces().size());
    }

    @Test
    void streamsModelContentBeforeCompletion() {
        var chunks = new ArrayList<SkillEngine.ContentDelta>();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Use streaming"); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                var response = recommendation(prompt);
                var completed = new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(response.getResult().getOutput(),
                        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build())));
                return Flux.just(completed).concatWith(Flux.defer(() -> {
                    assertFalse(chunks.isEmpty());
                    return Flux.empty();
                }));
            }
        };
        var run = engine(model, this::user).executeStreaming(SKILL, input("REAL"), event -> {}, chunks::add, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(1, chunks.get(0).modelRound());
    }

    @Test
    void contextIsBoundedAndSearchOnlyUsesVisibleMemoryNotIdentityOrMetadata() {
        var profile = MAPPER.createObjectNode().put("username", "手机");
        var memories = profile.putArray("memories");
        for (int index = 0; index < 20; index++) memories.addObject().put("memory", "音频" + "字".repeat(500))
                .put("source", "手机").putObject("metadata").put("skuid", "办公");
        JsonNode compact = FastRecommendationContext.compact(profile);
        assertEquals(6, compact.path("memories").size());
        assertTrue(compact.path("partial").asBoolean());
        assertEquals(400, compact.at("/memories/0/memory").asText().length());
        assertEquals(502, profile.at("/memories/0/memory").asText().length());
        String text = FastRecommendationContext.searchText(compact, MAPPER.createObjectNode());
        assertFalse(text.contains("手机"));
        assertFalse(text.contains("办公"));
    }

    @Test
    void sendsOneRealSpringAiHttpRequestWithLowReasoningAndNoToolSchemas() throws Exception {
        var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var request = MAPPER.readTree(exchange.getRequestBody());
            requests.add(request);
            var prompt = new Prompt(new org.springframework.ai.chat.messages.UserMessage(request.at("/messages/1/content").asText()));
            var output = recommendation(prompt).getResult().getOutput().getText();
            var chunk = java.util.Map.of("id", "fast-wire", "object", "chat.completion.chunk", "created", 1,
                    "model", "test-model", "choices", List.of(java.util.Map.of("index", 0,
                            "delta", java.util.Map.of("role", "assistant", "content", output), "finish_reason", "stop")));
            byte[] bytes = ("data: " + MAPPER.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var api = org.springframework.ai.openai.api.OpenAiApi.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).apiKey("test-only-key").build();
            var model = org.springframework.ai.openai.OpenAiChatModel.builder().openAiApi(api).build();
            var run = engine(model, this::user).executeStreaming(SKILL, input("REAL"), event -> {}, delta -> {}, () -> false);
            assertEquals("SUCCESS", run.status(), run.error());
            assertEquals(1, requests.size());
            assertEquals("low", requests.get(0).path("reasoning_effort").asText());
            assertEquals("test-model", requests.get(0).path("model").asText());
            assertTrue(requests.get(0).path("stream").asBoolean());
            assertTrue(requests.get(0).path("tools").isMissingNode() || requests.get(0).path("tools").isEmpty());
            assertEquals(2, requests.get(0).path("messages").size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidPreparationConfiguration() throws Exception {
        var resource = new ClassPathResource("skills/recommendation.json");
        String json = resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for (String broken : List.of(json.replace("PREPARED_RECOMMENDATION", "UNKNOWN"),
                json.replace("\"maxModelCalls\": 2,", "\"maxModelCalls\": 3,"),
                json.replace("\"reasoningEffort\": \"low\"", "\"reasoningEffort\": \"unsupported\""))) {
            var invalid = new org.springframework.core.io.ByteArrayResource(broken.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                public org.springframework.core.io.Resource createRelative(String relative) {
                    return new ClassPathResource("skills/" + relative);
                }
            };
            assertThrows(IllegalArgumentException.class, () -> new SkillCatalog(MAPPER, invalid, SkillEngineTests.registry()));
        }
    }
}
