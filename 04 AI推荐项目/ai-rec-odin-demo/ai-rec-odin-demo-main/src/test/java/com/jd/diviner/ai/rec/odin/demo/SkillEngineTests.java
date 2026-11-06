package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.fixtures.MockRecommendationTools;
import com.jd.diviner.ai.rec.odin.demo.app.tools.travel.BeijingTravelTools;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.junit.jupiter.api.Assertions.*;

class SkillEngineTests {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String FINAL = """
            {"summary":"模型生成的测试分析","needs":[{"category":"跑步","title":"改善日常慢跑体验",
             "confidence":87,"evidence":"浏览跑鞋8次","priority":"近期优先"}],
             "comparisonSummary":"资料不足，需进一步核实适用条件",
             "products":[{"productId":"SKU-001","score":92,"reason":"满足日常慢跑缓震需求",
             "comparison":{"status":"INSUFFICIENT_EVIDENCE","strengths":[],"limitations":[],
             "fit":"与慢跑需求相关","uncertainty":"当前资料不足，需核实实际性能"}}]}
            """;
    private ToolRegistry registry;
    private SkillCatalog catalog;
    private String configuration;

    @BeforeEach
    void setUp() throws Exception {
        registry = registry();
        try (var stream = agentCatalogResource().getInputStream()) {
            configuration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        catalog = catalog(configuration);
    }

    static Resource agentCatalogResource() {
        return new ClassPathResource("skills/recommendation.json");
    }

    static ToolRegistry registry() {
        BeijingTravelTools travel = new BeijingTravelTools(MAPPER, uri -> {
            throw new IllegalStateException("测试未提供旅游 HTTP fixture");
        }, java.time.Clock.systemUTC());
        var tools = new ArrayList<SkillTool>(List.of(
                MockRecommendationTools.profile(MAPPER), MockRecommendationTools.behaviors(MAPPER),
                MockRecommendationTools.products(MAPPER), travel.weather(), travel.attractions(), knowledgeTool()));
        tools.addAll(jevCidTools());
        return new ToolRegistry(tools);
    }

    static List<SkillTool> jevCidTools() {
        return List.of(
                testTool("select_cid1"),
                testTool("select_cid2"),
                testTool("recall_cid2"),
                testTool("i2i"));
    }

    private static SkillTool testTool(String name) {
        return new SkillTool() {
            public String name() { return name; }
            public String description() { return "JEV/CID test fixture"; }
            public String inputSchema() { return "{\"type\":\"object\"}"; }
            public JsonNode execute(JsonNode arguments) { return MAPPER.createObjectNode(); }
        };
    }

    static SkillTool knowledgeTool() {
        return new SkillTool() {
            public String name() { return "retrieve_product_knowledge"; }
            public String description() { return "Knowledge test fixture"; }
            public String inputSchema() { return "{\"type\":\"object\"}"; }
            public String dataMode() { return "LIVE"; }
            public JsonNode execute(JsonNode arguments) {
                var result = MAPPER.createObjectNode();
                var products = result.putArray("products");
                arguments.path("productIds").forEach(id -> products.addObject().put("productId", id.asText())
                        .put("status", "INSUFFICIENT_EVIDENCE").putArray("evidence"));
                return result;
            }
        };
    }

    static ChatResponse tool(String id, String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments))).build())));
    }

    static ChatResponse answer(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    static ChatResponse scriptedResponse(Prompt prompt) {
        long results = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();
        return switch ((int) results) {
            case 0 -> tool("call-skill", "Skill", "{\"command\":\"shopping-recommendation\"}");
            case 1 -> tool("call-profile", "get_user_profile", "{\"username\":\"xiaoming\"}");
            case 2 -> tool("call-behaviors", "get_user_behaviors", "{\"username\":\"xiaoming\"}");
            case 3 -> tool("call-search", "search_products", "{\"interests\":[\"跑步\"],\"budget\":800,\"limit\":8}");
            case 4 -> tool("call-knowledge", "retrieve_product_knowledge", "{\"productIds\":[\"SKU-001\"],\"question\":\"慢跑场景下的优点与使用限制\"}");
            default -> answer(FINAL);
        };
    }

    @Test
    void loadsSkillProgressivelyAndRunsNativeToolCallingLoop() {
        List<Prompt> requests = new ArrayList<>();
        SkillEngine.Run run = engine(prompt -> {
            requests.add(prompt);
            return scriptedResponse(prompt);
        }).execute("shopping-recommendation", input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(6, run.modelCalls());
        assertEquals("LIVE_MODEL_MIXED_DATA", run.mode());
        assertEquals(List.of("Skill", "get_user_profile", "get_user_behaviors", "search_products", "retrieve_product_knowledge"),
                run.traces().stream().map(SkillEngine.Trace::tool).toList());
        assertEquals("call-profile", run.traces().get(1).id());
        assertEquals(2, run.traces().get(1).modelRound());
        assertEquals("模型生成的测试分析", run.output().path("summary").asText());
        assertEquals(87, run.output().path("needs").get(0).path("confidence").asInt());
        assertEquals(399, run.output().path("products").get(0).path("product").path("price").asInt());
        assertEquals("xiaoming", run.output().path("profile").path("username").asText());
        assertFalse(requests.get(0).getContents().contains("## 工作方式"));
        assertEquals(List.of("Skill"), ((ToolCallingChatOptions) requests.get(0).getOptions()).getToolCallbacks()
                .stream().map(callback -> callback.getToolDefinition().name()).toList());
        assertFalse(((ToolCallingChatOptions) requests.get(0).getOptions()).getInternalToolExecutionEnabled());
        ToolResponseMessage loaded = (ToolResponseMessage) requests.get(1).getInstructions().get(3);
        assertTrue(loaded.getResponses().get(0).responseData().contains("## 工作方式"));
        List<ToolResponseMessage> outputs = requests.get(4).getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast).toList();
        assertEquals(4, outputs.size());
        assertEquals("call-search", outputs.get(3).getResponses().get(0).id());
        assertTrue(outputs.get(3).getResponses().get(0).responseData().contains("SKU-001"));
    }

    @Test
    void neverFallsBackToRulesWhenModelFails() {
        SkillEngine.Run run = engine(prompt -> { throw new IllegalStateException("api-key=secret"); })
                .execute("shopping-recommendation", input());
        assertEquals("FAILED", run.status());
        assertEquals(1, run.modelCalls());
        assertTrue(run.traces().isEmpty());
        assertTrue(run.output().isEmpty());
        assertFalse(MAPPER.valueToTree(run).toString().contains("secret"));
    }

    @Test
    void rejectsPrematureAnswerWithoutToolExecution() {
        SkillEngine.Run run = engine(prompt -> answer(FINAL)).execute("shopping-recommendation", input());
        assertEquals("FAILED", run.status());
        assertTrue(run.traces().isEmpty());
        assertTrue(run.error().contains("提前结束"));
    }

    @Test
    void blocksUnauthorizedToolCallsBeforeExecution() {
        SkillEngine.Run run = engine(prompt -> tool("bad", "execute_shell", "{}"))
                .execute("shopping-recommendation", input());
        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("未授权"));
        assertTrue(run.traces().isEmpty());
    }

    @Test
    void cannotLoadAnotherSkillOrQueryAnotherUser() {
        SkillEngine.Run wrongSkill = engine(prompt -> tool("first", "Skill", "{\"command\":\"user-insight\"}"))
                .execute("shopping-recommendation", input());
        assertEquals("FAILED", wrongSkill.status());
        SkillEngine.Run wrongUser = engine(prompt -> prompt.getInstructions().size() == 2
                ? scriptedResponse(prompt) : tool("user", "get_user_profile", "{\"username\":\"another-user\"}"))
                .execute("shopping-recommendation", input());
        assertEquals("FAILED", wrongUser.status());
        assertEquals(2, wrongUser.traces().size());
        assertEquals("FAILED", wrongUser.traces().get(1).status());
        assertTrue(wrongUser.traces().get(1).error().contains("用户标识与本次会话不一致"));
        assertFalse(wrongUser.traces().get(1).error().contains("AI Memory"));
    }

    @Test
    void sessionUserToolsAcceptEmptyArgumentsForRealAndDemoModes() {
        for (String mode : List.of("REAL", "DEMO")) {
            var run = engine(prompt -> {
                var response = scriptedResponse(prompt);
                if (!response.hasToolCalls()) return response;
                var call = response.getResult().getOutput().getToolCalls().get(0);
                if (!List.of("get_user_profile", "get_user_behaviors").contains(call.name())) return response;
                var callback = ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream()
                        .filter(tool -> call.name().equals(tool.getToolDefinition().name())).findFirst().orElseThrow();
                assertTrue(callback.getToolDefinition().inputSchema().contains("\"enum\":[\"xiaoming\"]"));
                return tool(call.id(), call.name(), "{}");
            }).execute("shopping-recommendation", MAPPER.createObjectNode().put("username", "xiaoming").put("userDataMode", mode));
            assertEquals("SUCCESS", run.status(), run.error());
            assertEquals("xiaoming", run.output().at("/profile/username").asText());
            assertTrue(run.traces().get(1).arguments().isEmpty());
        }
    }

    @Test
    void failedUserQueryDoesNotMisreportSkippedBehaviorAsMemoryOutage() {
        var run = engine(prompt -> prompt.getInstructions().size() == 2 ? scriptedResponse(prompt)
                : new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("profile", "function", "get_user_profile", "{\"username\":123}"),
                                new AssistantMessage.ToolCall("behaviors", "function", "get_user_behaviors", "{}"))).build()))))
                .execute("shopping-recommendation", input());
        assertEquals("FAILED", run.status());
        assertEquals(3, run.traces().size());
        assertTrue(run.traces().get(1).error().contains("用户查询参数不合法"));
        assertEquals("前序工具已失败，本次调用未执行", run.traces().get(2).error());
        assertNull(run.traces().get(2).output());
        assertFalse(MAPPER.valueToTree(run).toString().contains("MEMORY_REQUEST_FAILED"));
    }

    @Test
    void rejectsMalformedToolArguments() {
        SkillEngine.Run run = engine(prompt -> tool("bad", "Skill", "{"))
                .execute("shopping-recommendation", input());
        assertEquals("FAILED", run.status());
        assertEquals("FAILED", run.traces().get(0).status());
    }

    @Test
    void boundsModelAndToolCalls() {
        SkillCatalog bounded = catalog(configuration.replace("\"maxModelCalls\": 8", "\"maxModelCalls\": 2"));
        SkillEngine.Run run = new SkillEngine(MAPPER, bounded, registry, new SkillModelClient(SkillEngineTests::scriptedResponse, "test"))
                .execute("shopping-recommendation", input());
        assertEquals("FAILED", run.status());
        assertEquals(2, run.modelCalls());
        assertTrue(run.error().contains("轮数上限"));
        SkillCatalog toolBounded = catalog(configuration.replace("\"maxToolCalls\": 8", "\"maxToolCalls\": 1"));
        run = new SkillEngine(MAPPER, toolBounded, registry, new SkillModelClient(SkillEngineTests::scriptedResponse, "test"))
                .execute("shopping-recommendation", input());
        assertTrue(run.error().contains("工具调用次数上限"));
        assertEquals(1, run.traces().size());
    }

    @Test
    void rejectsInventedProductsAndMalformedFinalAnswer() {
        for (String invalid : List.of(FINAL.replace("SKU-001", "invented"), "not json", "{}", FINAL.replace("\"score\":92", "\"score\":999"))) {
            SkillEngine.Run run = engine(prompt -> {
                ChatResponse response = scriptedResponse(prompt);
                return response.hasToolCalls() ? response : answer(invalid);
            }).execute("shopping-recommendation", input());
            assertEquals("FAILED", run.status());
            assertEquals(5, run.traces().size());
        }
    }

    @Test
    void rejectsDuplicateCallIds() {
        SkillEngine.Run run = engine(prompt -> prompt.getInstructions().size() == 2 ? scriptedResponse(prompt)
                : tool("call-skill", "get_user_profile", "{\"username\":\"xiaoming\"}"))
                .execute("shopping-recommendation", input());
        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("重复"));
    }

    @Test
    void validatesConfigurationAndLocalInstructionPaths() {
        assertThrows(IllegalArgumentException.class, () -> catalog(configuration.replace("\"version\": 2", "\"version\": 1")));
        assertThrows(IllegalArgumentException.class, () -> catalog(configuration.replace("get_user_profile", "execute_shell")));
        assertThrows(IllegalArgumentException.class, () -> catalog(configuration.replace("shopping-recommendation/SKILL.md", "../SKILL.md")));
        assertThrows(IllegalStateException.class, () -> catalog(configuration.replace("shopping-recommendation/SKILL.md", "missing/SKILL.md")));
        assertThrows(IllegalArgumentException.class, () -> catalog(configuration.replace("\"user-insight\"", "\"shopping-recommendation\"")));
        assertThrows(IllegalStateException.class, () -> catalog(configuration.replace("\"version\": 2", "\"version\": 2, \"typo\": true")));
        assertThrows(IllegalStateException.class, () -> catalog("{"));
    }

    @Test
    void rejectsUnknownDisabledAndMissingInputWithoutCallingModel() {
        SkillEngine engine = engine(prompt -> { fail("Model must not be called"); return null; });
        assertThrows(SkillEngine.UnknownSkillException.class, () -> engine.execute("missing", input()));
        assertThrows(IllegalArgumentException.class, () -> engine.execute("shopping-recommendation", MAPPER.createObjectNode()));
        SkillCatalog disabled = catalog(configuration.replace("\"enabled\": true", "\"enabled\": false"));
        assertTrue(disabled.enabledSkills().isEmpty());
        assertThrows(SkillEngine.UnknownSkillException.class, () -> new SkillEngine(MAPPER, disabled, registry,
                new SkillModelClient(prompt -> { fail("Model must not be called"); return null; }, "test"))
                .execute("shopping-recommendation", input()));
    }

    @Test
    void isolatesParallelRunsAndToolHistories() {
        SkillEngine engine = engine(SkillEngineTests::scriptedResponse);
        List<CompletableFuture<SkillEngine.Run>> runs = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> engine.execute("shopping-recommendation", input()))).toList();
        runs.forEach(future -> {
            assertEquals("SUCCESS", future.join().status());
            assertEquals(5, future.join().traces().size());
            assertEquals(6, future.join().modelCalls());
        });
        assertEquals(6, runs.stream().map(future -> future.join().runId()).distinct().count());
    }

    private SkillCatalog catalog(String json) {
        return new SkillCatalog(MAPPER, new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)) {
            public Resource createRelative(String relativePath) { return new ClassPathResource("skills/" + relativePath); }
        }, registry);
    }

    private SkillEngine engine(ChatModel model) {
        return new SkillEngine(MAPPER, catalog, registry, new SkillModelClient(model, "test-model"));
    }

    private JsonNode input() {
        return MAPPER.valueToTree(Map.of("username", "xiaoming"));
    }
}
