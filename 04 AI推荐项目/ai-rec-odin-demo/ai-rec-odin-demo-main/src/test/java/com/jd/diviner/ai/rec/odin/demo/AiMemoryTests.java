package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.memory.AiMemoryClient;
import com.jd.diviner.ai.rec.odin.demo.app.tools.user.AiMemoryUserTools;
import com.jd.diviner.ai.rec.odin.demo.app.tools.user.SessionUserTool;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.bootstrap.ToolConfiguration;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation.ShoppingPredictionValidator;
import com.jd.diviner.ai.rec.odin.demo.web.controller.SkillController;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiMemoryTests {
    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> payload = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private AiMemoryUserTools tools;
    private static final String RECORD = """
            {"_id":"record-1","memory":"品类偏好：户外装备","memory_type":"long_term_memory","role":"user",
             "user_id":"server-one-id","create_time":1772443057,"metadata":{"memory_subtype2":"4.1","reason":"private-reason"}}
            """;

    @Test
    void businessUuidIsPassedUnchangedToMemoryUserIdForBothTools() {
        for (String userId : List.of("315c70c8f4a25681", "315C70c8f4a25681", "550e8400-e29b-41d4-a716-446655440000")) {
            int before = requests.size();
            var arguments = SkillEngineTests.MAPPER.createObjectNode().put("username", " " + userId + " ");
            assertEquals(userId, tools.profile().execute(arguments).path("username").asText());
            assertEquals(userId, tools.behaviors().execute(arguments).path("username").asText());
            assertEquals(before + 2, requests.size());
            for (int index = before; index < requests.size(); index++) {
                assertEquals(userId, requests.get(index).path("user_id").asText());
                assertFalse(requests.get(index).has("pin"));
                assertFalse(requests.get(index).has("username"));
            }
        }
        assertTrue(tools.profile().inputSchema().contains("用户 UUID"));
        assertTrue(tools.behaviors().inputSchema().contains("用户 UUID"));
        var error = assertThrows(IllegalArgumentException.class, () -> AiMemoryUserTools.validateUserId(""));
        assertTrue(error.getMessage().contains("UUID"));
    }

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            try {
                assertEquals("POST", exchange.getRequestMethod());
                assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                JsonNode request = SkillEngineTests.MAPPER.readTree(exchange.getRequestBody());
                requests.add(request);
                String type = request.path("search_cfg").fieldNames().next();
                String body = payload.get();
                if (body == null) body = "{\"code\":0,\"results\":{\"" + type + "\":{\"code\":0,\"memories\":[]}}}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("Location", "/search");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        tools = new AiMemoryUserTools(SkillEngineTests.MAPPER, client(Duration.ofSeconds(2)));
    }

    private AiMemoryClient client(Duration timeout) {
        return new AiMemoryClient(SkillEngineTests.MAPPER, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search"), timeout, 20);
    }

    private JsonNode input() { return SkillEngineTests.MAPPER.createObjectNode().put("username", "ctlihua"); }

    @Test
    void sessionBindingUsesOriginalUuidWithoutModelSuppliedIdentifier() throws Exception {
        String uuid = "315C70c8f4a25681";
        for (var delegate : List.of(tools.profile(), tools.behaviors())) {
            var bound = new SessionUserTool(SkillEngineTests.MAPPER, delegate, uuid);
            assertEquals("LIVE", bound.dataMode());
            bound.execute(SkillEngineTests.MAPPER.createObjectNode());
            bound.execute(SkillEngineTests.MAPPER.createObjectNode().put("username", " " + uuid + " "));
            for (String invalid : List.of("{\"username\":123}", "{\"username\":null}", "{\"username\":\"other\"}",
                    "{\"pin\":\"other\"}", "{\"username\":\"315C70c8f4a25681\",\"userDataMode\":\"DEMO\"}")) {
                assertThrows(SessionUserTool.InvalidArguments.class, () -> bound.execute(SkillEngineTests.MAPPER.readTree(invalid)));
            }
        }
        assertEquals(4, requests.size());
        assertTrue(requests.stream().allMatch(request -> uuid.equals(request.path("user_id").asText())));
    }

    @Test
    void responseValidationFailureIsDistinguishedFromTransportFailure() {
        payload.set("{\"results\":{\"long_term_memory\":{\"memories\":[{\"memory\":{}}]}}}");
        var run = engine(false, new AtomicInteger()).execute("user-insight", input());
        assertEquals("FAILED", run.status());
        assertEquals(1, requests.size());
        assertTrue(run.traces().get(1).error().contains("MEMORY_NORMALIZATION_FAILED"));
        assertFalse(run.traces().get(1).error().contains("MEMORY_REQUEST_FAILED"));
    }

    @Test
    void memoryTransportFailureRetainsDiagnosticCause() {
        status.set(503);
        var failure = assertThrows(IllegalStateException.class, () -> tools.profile().execute(input()));
        assertNotNull(failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("HTTP 503"));
    }

    @AfterEach
    void close() { server.stop(0); }

    @Test
    void sendsDocumentedPostAndMapsRealLongTermMemoryWithoutInventingProfile() {
        payload.set("{\"code\":0,\"results\":{\"long_term_memory\":{\"code\":0,\"memories\":[" + RECORD + "]}}}");
        var profile = tools.profile().execute(input());
        assertEquals("LIVE", tools.profile().dataMode());
        assertEquals("AI_MEMORY", profile.path("source").asText());
        assertEquals("AVAILABLE", profile.path("dataStatus").asText());
        assertTrue(profile.path("budget").isNull());
        assertTrue(profile.path("city").isNull());
        assertEquals("品类偏好：户外装备", profile.at("/memories/0/memory").asText());
        assertEquals(profile.at("/memories/0/memory"), profile.at("/interests/0"));
        assertEquals(1772443057, profile.at("/memories/0/create_time").asLong());
        assertFalse(profile.toString().contains("private-reason"));
        assertFalse(profile.toString().contains("server-one-id"));
        JsonNode request = requests.get(0);
        assertEquals("ctlihua", request.path("user_id").asText());
        assertEquals("", request.path("query").asText());
        assertFalse(request.has("clientId"));
        assertEquals(20, request.at("/search_cfg/long_term_memory/limit").asInt());
        assertFalse(request.at("/search_cfg/long_term_memory/vsearch").asBoolean());
        assertEquals("user", request.at("/search_cfg/long_term_memory/filters/role").asText());
    }

    @Test
    void behaviorSignalsKeepActionTimeAndSkuWithoutConvertingClicksIntoPurchases() {
        payload.set("""
                {"origin_memory":[{"memory":"商品名称：轻量背包","memory_type":"origin_memory","role":"user",
                "create_time":1773288468,"metadata":{"memory_subtype2":"click","skuid":123456}}]}
                """);
        var behavior = tools.behaviors().execute(input());
        assertEquals("LIVE", tools.behaviors().dataMode());
        assertEquals("[行为类型 click] [原始时间 1773288468] 商品名称：轻量背包", behavior.at("/signals/0").asText());
        assertEquals(123456, behavior.at("/memories/0/metadata/skuid").asInt());
        assertEquals(1, requests.get(0).path("search_cfg").size());
        assertTrue(requests.get(0).at("/search_cfg/origin_memory/vsearch").isBoolean());
    }

    @Test
    void supportsTopLevelAndWrappedGroupsButNeverTreatsMissingOrFailedGroupsAsEmpty() {
        for (String json : List.of("{\"long_term_memory\":[]}", "{\"results\":{\"long_term_memory\":[]}}",
                "{\"code\":\"0\",\"long_term_memory\":{\"memories\":[]}}")) {
            payload.set(json);
            assertEquals("EMPTY", tools.profile().execute(input()).path("dataStatus").asText());
        }
        for (String json : List.of("{}", "{\"code\":4000,\"msg\":\"private-secret\",\"long_term_memory\":[]}",
                "{\"results\":{\"long_term_memory\":{\"code\":500,\"memories\":[]}}}", "null", "<html>login</html>",
                "{\"long_term_memory\":{\"memories\":null}}")) {
            payload.set(json);
            var failure = assertThrows(IllegalStateException.class, () -> tools.profile().execute(input()));
            assertFalse(failure.toString().contains("private-secret"));
        }
    }

    @Test
    void rejectsRedirectHttpFailuresOversizedResponsesAndInvalidMemoryRecords() {
        for (int code : List.of(302, 401, 429, 500)) {
            status.set(code);
            int before = requests.size();
            assertThrows(IllegalStateException.class, () -> tools.profile().execute(input()));
            assertEquals(before + 1, requests.size());
        }
        status.set(200);
        payload.set("x".repeat(1024 * 1024 + 1));
        assertThrows(IllegalStateException.class, () -> tools.profile().execute(input()));
        for (String record : List.of("null", "{\"memory\":42}", "{\"memory\":\"text\",\"role\":\"agent\"}",
                "{\"memory\":\"text\",\"memory_type\":\"origin_memory\"}", "{\"memory\":\"text\",\"metadata\":[]}",
                "{\"memory\":\"" + "x".repeat(24001) + "\"}")) {
            payload.set("{\"long_term_memory\":[" + record + "]}");
            assertThrows(IllegalStateException.class, () -> tools.profile().execute(input()));
        }
    }

    @Test
    void networkWaitIsBoundedAndConfigurationAndPinAreValidated() throws Exception {
        server.removeContext("/search");
        server.createContext("/search", exchange -> {
            try { Thread.sleep(300); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(IllegalStateException.class,
                () -> client(Duration.ofMillis(40)).search("ctlihua", "origin_memory")));
        for (String json : List.of("{}", "{\"username\":5}", "{\"username\":\"a\\nb\"}", "{\"username\":\"x\",\"url\":\"http://other\"}")) {
            assertThrows(IllegalArgumentException.class, () -> tools.profile().execute(SkillEngineTests.MAPPER.readTree(json)));
        }
        assertThrows(IllegalArgumentException.class, () -> new AiMemoryClient(SkillEngineTests.MAPPER, URI.create("file:/tmp/data"), Duration.ofSeconds(1), 20));
        assertThrows(IllegalArgumentException.class, () -> client(Duration.ofSeconds(31)));
    }

    private SkillEngine engine(boolean hallucinate, AtomicInteger calls) {
        return engine(hallucinate, calls, "user-insight", "ctlihua");
    }

    private SkillEngine engine(boolean hallucinate, AtomicInteger calls, String skillId, String pin) {
        var registry = new ToolRegistry(List.of(tools.profile(), tools.behaviors()));
        var resource = new org.springframework.core.io.ByteArrayResource("""
                {"version":2,"skills":[{"id":"user-insight","name":"用户洞察","description":"用户记忆测试","enabled":true,
                "requiredInputs":["username"],"instructions":"user-insight/SKILL.md","tools":["get_user_profile","get_user_behaviors"],
                "requiredTools":["get_user_profile","get_user_behaviors"],"requiredOutputs":["summary","needs"],"maxModelCalls":6,"maxToolCalls":5}]}
                """.replace("user-insight", skillId).getBytes(StandardCharsets.UTF_8)) {
            @Override
            public org.springframework.core.io.Resource createRelative(String path) { return new ClassPathResource("skills/" + path); }
        };
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, resource, registry);
        return new SkillEngine(SkillEngineTests.MAPPER, catalog, registry, new SkillModelClient(prompt -> {
            calls.incrementAndGet();
            long completed = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();
            if ("FINAL_ANSWER".equals(StandardSkillTests.facts(prompt).path("phase").asText())) completed = 3;
            return switch ((int) completed) {
                case 0 -> SkillEngineTests.tool("load", "Skill", "{\"command\":\"" + skillId + "\"}");
                case 1 -> SkillEngineTests.tool("profile", "get_user_profile", "{\"username\":\"" + pin + "\"}");
                case 2 -> SkillEngineTests.tool("behavior", "get_user_behaviors", "{\"username\":\"" + pin + "\"}");
                default -> SkillEngineTests.answer(hallucinate ? "{\"summary\":\"猜测\",\"needs\":[{}]}"
                        : "{\"summary\":\"当前记忆为空，无法判断偏好，请补充需求\",\"needs\":[]}");
            };
        }, "test-model"));
    }

    @Test
    void explicitDemoIsRequestScopedAndNeverCallsMemoryWhileSamePinDefaultsToReal() {
        var calls = new AtomicInteger();
        var engine = engine(false, calls, "shopping-recommendation", "xiaoming");
        var input = SkillEngineTests.MAPPER.createObjectNode().put("username", "xiaoming").put("userDataMode", "DEMO");
        status.set(503);
        var demo = engine.execute("shopping-recommendation", input);
        assertEquals("SUCCESS", demo.status(), demo.error());
        assertEquals(800, demo.output().at("/profile/budget").asInt());
        assertFalse(demo.output().at("/profile/behaviors").isEmpty());
        assertEquals(4, calls.get());
        assertTrue(requests.isEmpty());
        assertFalse(demo.output().toString().contains("MOCK"));
        input.remove("userDataMode");
        var failed = engine.execute("shopping-recommendation", input);
        assertEquals("FAILED", failed.status());
        assertEquals(1, requests.size());
        assertEquals("xiaoming", requests.get(0).path("user_id").asText());
        status.set(200);
        var real = engine.execute("shopping-recommendation", input.put("userDataMode", "REAL"));
        assertEquals("SUCCESS", real.status(), real.error());
        assertEquals("AI_MEMORY", real.output().at("/profile/source").asText());
        assertTrue(real.output().at("/profile/budget").isNull());
        assertEquals(3, requests.size());
    }

    @Test
    void rejectsInvalidModeDemoUserOtherSkillsAndCrossUserToolCalls() throws Exception {
        var calls = new AtomicInteger();
        var engine = engine(false, calls, "shopping-recommendation", "xiaoming");
        for (String json : List.of("{\"username\":\"xiaoming\",\"userDataMode\":null}",
                "{\"username\":\"xiaoming\",\"userDataMode\":\"AUTO\"}",
                "{\"username\":\"ctlihua\",\"userDataMode\":\"DEMO\"}")) {
            assertThrows(IllegalArgumentException.class, () -> engine.execute("shopping-recommendation", SkillEngineTests.MAPPER.readTree(json)));
        }
        assertEquals(0, calls.get());
        var demo = SkillEngineTests.MAPPER.createObjectNode().put("username", "xiaoyu").put("userDataMode", "DEMO");
        assertThrows(IllegalArgumentException.class, () -> engine(false, calls).execute("user-insight", demo));
        assertEquals("FAILED", engine.execute("shopping-recommendation", demo).status());
        assertTrue(requests.isEmpty());
    }

    @Test
    void demoChoiceEndpointReturnsOriginalThreeUsersWithoutMemoryRequests() throws Exception {
        var registry = SkillEngineTests.registry();
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, new ClassPathResource("skills/recommendation.json"), registry);
        var mvc = MockMvcBuilders.standaloneSetup(new SkillController(catalog, engine(false, new AtomicInteger()), registry)).build();
        mvc.perform(get("/api/skills/demo-users")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].username").value("xiaoming"))
                .andExpect(jsonPath("$.data[1].username").value("xiaoyu"))
                .andExpect(jsonPath("$.data[2].username").value("laowang"))
                .andExpect(jsonPath("$.data[0].budget").value(800));
        assertTrue(requests.isEmpty());
    }

    @Test
    void realHttpToolsFeedModelLoopAndPreserveHonestEmptyData() {
        var calls = new AtomicInteger();
        var run = engine(false, calls).execute("user-insight", input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(4, calls.get());
        assertEquals(2, requests.size());
        assertEquals("ctlihua", run.output().at("/profile/username").asText());
        assertTrue(run.output().at("/profile/behaviors").isEmpty());
        assertTrue(run.output().at("/profile/budget").isNull());
        assertEquals("LIVE_MODEL_LIVE_DATA", run.mode());
        var rejected = engine(true, new AtomicInteger()).execute("user-insight", input());
        assertEquals("FAILED", rejected.status());
        assertTrue(rejected.error().contains("记忆为空"));
    }

    @Test
    void memoryFailureStopsModelAndToolsWithoutSubstitutingPredefinedUsers() {
        status.set(503);
        var calls = new AtomicInteger();
        var run = engine(false, calls).execute("user-insight", input());
        assertEquals("FAILED", run.status());
        assertEquals(2, calls.get());
        assertEquals(1, requests.size());
        assertFalse(run.traces().stream().anyMatch(trace -> trace.tool().equals("get_user_behaviors")));
    }

    @Test
    void configuredBeansUseLiveToolsAndControllerKeepsOriginalUserId() throws Exception {
        var config = new ToolConfiguration();
        assertEquals("LIVE", config.userProfileTool(tools).dataMode());
        assertEquals("LIVE", config.userBehaviorsTool(tools).dataMode());
        var registry = SkillEngineTests.registry();
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, new ClassPathResource("skills/recommendation.json"), registry);
        var controller = new SkillController(catalog, engine(false, new AtomicInteger()), registry);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "userUuids", "ctlihua,other-pin,ctlihua");
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/skills/users")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        assertTrue(requests.isEmpty());
        mvc.perform(post("/api/skills/user-insight/run").contentType("application/json").content("{\"input\":{\"username\":\" ctlihua \"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.output.profile.username").value("ctlihua"));
    }

    @Test
    void predictionEvidenceCanReferenceRealMemoriesButNotUnknownBudgetOrEmptyInterests() throws Exception {
        payload.set("{\"long_term_memory\":[" + RECORD + "]}");
        var profile = tools.profile().execute(input());
        String json = """
                {"predictions":[],"preferences":[{"dimension":"品类","preference":"户外装备","reasoning":"依据长期记忆",
                "confidence":50,"evidence":[{"source":"get_user_profile","path":"/memories/0/memory"}]}],"uncertainties":["未确认本次购买意愿"]}
                """;
        var output = SkillEngineTests.MAPPER.readTree(json);
        ShoppingPredictionValidator.validate(output, Map.of("get_user_profile", profile));
        assertEquals("品类偏好：户外装备", output.at("/preferences/0/evidence/0/value").asText());
        assertThrows(IllegalArgumentException.class, () -> ShoppingPredictionValidator.validate(
                SkillEngineTests.MAPPER.readTree(json.replace("/memories/0/memory", "/budget")), Map.of("get_user_profile", profile)));
    }

    @Test
    void shoppingCompletesRequiredToolsWithEmptyMemoryAndRejectsFabricatedBudgetOrInterests() {
        var base = SkillEngineTests.registry();
        var registered = new java.util.ArrayList<>(base.describe().stream().map(item -> base.require(item.get("name")))
                .filter(tool -> !List.of("get_user_profile", "get_user_behaviors", "search_products").contains(tool.name())).toList());
        registered.add(tools.profile());
        registered.add(tools.behaviors());
        registered.add(new com.jd.diviner.ai.rec.odin.demo.app.tools.product.JdSearchProductsTool(SkillEngineTests.MAPPER,
                new com.jd.diviner.ai.rec.odin.demo.app.domain.product.JdProductCatalog(SkillEngineTests.MAPPER,
                        new ClassPathResource("catalog/jd-products.json"), java.time.Clock.systemUTC(), Duration.ofDays(7))));
        var registry = new ToolRegistry(registered);
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, new ClassPathResource("skills/recommendation.json"), registry);
        for (String search : List.of("{\"interests\":[],\"budget\":null,\"limit\":8}",
                "{\"interests\":[],\"budget\":800,\"limit\":8}", "{\"interests\":[\"户外\"],\"budget\":null,\"limit\":8}")) {
            var engine = new SkillEngine(SkillEngineTests.MAPPER, catalog, registry, new SkillModelClient(prompt -> {
                long completed = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();
                return switch ((int) completed) {
                    case 0 -> SkillEngineTests.tool("load", "Skill", "{\"command\":\"shopping-recommendation\"}");
                    case 1 -> SkillEngineTests.tool("profile", "get_user_profile", input().toString());
                    case 2 -> SkillEngineTests.tool("behavior", "get_user_behaviors", input().toString());
                    case 3 -> SkillEngineTests.tool("search", "search_products", search);
                    case 4 -> SkillEngineTests.tool("knowledge", "retrieve_product_knowledge", "{\"productIds\":[],\"question\":\"暂无用户记忆\"}");
                    default -> SkillEngineTests.answer("{\"summary\":\"暂无记忆，请补充需求\",\"needs\":[],\"products\":[],\"comparisonSummary\":\"无商品可对比\"}");
                };
            }, "test-model"));
            var run = engine.execute("shopping-recommendation", input());
            boolean valid = search.equals("{\"interests\":[],\"budget\":null,\"limit\":8}");
            assertEquals(valid ? "SUCCESS" : "FAILED", run.status(), run.error());
            if (valid) {
                assertEquals(5, run.traces().size());
                assertTrue(run.output().path("products").isEmpty());
                assertTrue(run.output().at("/profile/budget").isNull());
            }
        }
    }

    @Test
    void modelCannotQueryAnotherPinBeforeOrAfterMemoryCalls() {
        var base = SkillEngineTests.registry();
        var registered = new java.util.ArrayList<>(base.describe().stream().map(item -> base.require(item.get("name")))
                .filter(tool -> !List.of("get_user_profile", "get_user_behaviors").contains(tool.name())).toList());
        registered.add(tools.profile());
        registered.add(tools.behaviors());
        var registry = new ToolRegistry(registered);
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, new ClassPathResource("skills/recommendation.json"), registry);
        var engine = new SkillEngine(SkillEngineTests.MAPPER, catalog, registry, new SkillModelClient(prompt -> {
            if (prompt.getInstructions().stream().noneMatch(ToolResponseMessage.class::isInstance)) {
                return SkillEngineTests.tool("load", "Skill", "{\"command\":\"user-insight\"}");
            }
            return SkillEngineTests.tool("profile", "get_user_profile", "{\"username\":\"another-pin\"}");
        }, "test-model"));
        assertEquals("FAILED", engine.execute("user-insight", input()).status());
        assertTrue(requests.isEmpty());
    }
}
