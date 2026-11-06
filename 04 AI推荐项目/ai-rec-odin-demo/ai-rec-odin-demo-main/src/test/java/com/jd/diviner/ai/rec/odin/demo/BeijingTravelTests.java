package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.tools.travel.BeijingTravelTools;
import com.jd.diviner.ai.rec.odin.demo.app.domain.travel.BeijingTravelValidator;
import com.jd.diviner.ai.rec.odin.demo.fixtures.MockRecommendationTools;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.travel.TravelHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class BeijingTravelTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-29T04:00:00Z"), ZoneOffset.UTC);
    private static final String FINAL = """
            {"summary":"根据天气预报、景点榜单及用户兴趣，优先安排历史文化游。",
             "recommendations":[{"attractionId":"75595","date":"2026-09-30","interestReason":"喜欢历史文化",
               "weatherReason":"晴天，适合步行","precautions":"出发前复查天气及预约要求","alternative":"雨天改期"}],
             "travelTips":["榜单热度不是实时客流","山区天气不同，请查询当地预警"]}
            """;

    static ObjectNode weatherFixture() {
        ObjectNode result = MAPPER.createObjectNode().put("timezone", "Asia/Shanghai");
        result.set("daily_units", MAPPER.valueToTree(Map.of("temperature_2m_max", "°C", "temperature_2m_min", "°C",
                "precipitation_probability_max", "%", "precipitation_sum", "mm", "wind_speed_10m_max", "km/h")));
        ObjectNode daily = result.putObject("daily");
        daily.set("time", MAPPER.valueToTree(java.util.stream.IntStream.range(0, 8)
                .mapToObj(offset -> LocalDate.of(2026, 9, 29).plusDays(offset).toString()).toList()));
        for (var field : Map.of("weather_code", 0, "temperature_2m_max", 24, "temperature_2m_min", 15,
                "precipitation_probability_max", 10, "precipitation_sum", 0, "wind_speed_10m_max", 12).entrySet()) {
            daily.set(field.getKey(), MAPPER.valueToTree(java.util.Collections.nCopies(8, field.getValue())));
        }
        return result;
    }

    static String attractionsFixture() {
        return """
                <html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"initialState":{
                "rankingDetail":{"rankingId":100001406540,"businessType":"sight","title":"必打卡景点榜","changeTime":1790651445940},
                "rankingItemInfo":{"itemList":[{"basicInfo":{"businessId":"75595","businessName":"故宫博物院","rank":1,
                "jumpUrl":{"commonUrl":"https://m.ctrip.com/webapp/you/gspoi/sight/1/0.html?poiId=75595"}},
                "extInfo":{"districtInfo":{"name":"北京"},"shortFeature":"皇家宫殿群","location":"北京","hotScore":"10",
                "commentInfo":{"commentScore":"4.9","commentCount":"20.2w"}}}]}}}}}</script></html>
                """;
    }

    private BeijingTravelTools tools() {
        return new BeijingTravelTools(MAPPER, uri -> uri.getHost().equals("api.open-meteo.com")
                ? weatherFixture().toString() : attractionsFixture(), CLOCK);
    }

    private ObjectNode input() {
        return MAPPER.createObjectNode().put("username", "xiaoming").put("interests", "历史文化、摄影");
    }

    private SkillEngine engine(BeijingTravelTools tools, ChatModel model) {
        var registered = new ArrayList<>(List.of(MockRecommendationTools.profile(MAPPER),
                MockRecommendationTools.behaviors(MAPPER), MockRecommendationTools.products(MAPPER),
                tools.weather(), tools.attractions(), SkillEngineTests.knowledgeTool()));
        registered.addAll(SkillEngineTests.jevCidTools());
        ToolRegistry registry = new ToolRegistry(registered);
        SkillCatalog catalog = new SkillCatalog(MAPPER, new ClassPathResource("skills/recommendation.json"), registry);
        return new SkillEngine(MAPPER, catalog, registry, new SkillModelClient(model, "test-model"));
    }

    private ChatResponse scripted(Prompt prompt) {
        long results = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();
        return switch ((int) results) {
            case 0 -> SkillEngineTests.tool("travel-skill", "Skill", "{\"command\":\"beijing-weather-travel\"}");
            case 1 -> SkillEngineTests.tool("travel-profile", "get_user_profile", "{\"username\":\"xiaoming\"}");
            case 2 -> SkillEngineTests.tool("travel-weather", "get_beijing_weather", "{\"days\":5}");
            case 3 -> SkillEngineTests.tool("travel-attractions", "get_beijing_hot_attractions", "{\"limit\":7}");
            case 4 -> SkillEngineTests.tool("travel-behaviors", "get_user_behaviors", "{\"username\":\"xiaoming\"}");
            default -> SkillEngineTests.answer(FINAL);
        };
    }

    @Test
    void sendsRealHttpRequestsAndParsesProviderFixtures() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            byte[] body = (exchange.getRequestURI().getPath().equals("/v1/forecast")
                    ? weatherFixture().toString() : attractionsFixture()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            TravelHttpClient client = new TravelHttpClient();
            BeijingTravelTools tools = new BeijingTravelTools(MAPPER, uri -> client.get(URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()))), CLOCK);
            JsonNode weather = tools.weather().execute(MAPPER.readTree("{\"days\":5}"));
            assertEquals(5, weather.path("forecast").size());
            assertEquals("2026-09-30", weather.at("/forecast/0/date").asText());
            assertEquals("2026-10-04", weather.at("/forecast/4/date").asText());
            assertEquals("LIVE", weather.at("/source/dataMode").asText());
            assertEquals(24, weather.at("/forecast/0/temperatureMaxC").asInt());
            assertTrue(requests.get(0).contains("forecast_days=6"));
            assertTrue(requests.get(0).contains("latitude=39.9042"));
            JsonNode attractions = tools.attractions().execute(MAPPER.readTree("{\"limit\":7}"));
            assertEquals("故宫博物院", attractions.at("/attractions/0/name").asText());
            assertEquals("20.2w", attractions.at("/attractions/0/reviewCountDisplay").asText());
            assertEquals(1, attractions.path("availableCount").asInt());
            assertEquals("LIVE", attractions.at("/source/dataMode").asText());
            assertTrue(requests.get(1).endsWith("/100001406540.html"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesBeijingDateAtUtcDayBoundary() {
        BeijingTravelTools tools = new BeijingTravelTools(MAPPER, uri -> weatherFixture().toString(),
                Clock.fixed(Instant.parse("2026-09-29T16:01:00Z"), ZoneOffset.UTC));
        JsonNode result = tools.weather().execute(MAPPER.createObjectNode().put("days", 1));
        assertEquals("2026-10-01", result.at("/forecast/0/date").asText());
    }

    @Test
    void rejectsInvalidArgumentsBeforeNetworkCall() throws Exception {
        BeijingTravelTools tools = new BeijingTravelTools(MAPPER, uri -> { fail("Network must not run"); return ""; }, CLOCK);
        for (String json : List.of("{}", "{\"days\":0}", "{\"days\":8}", "{\"days\":1.5}", "{\"days\":\"5\"}",
                "{\"days\":1,\"url\":\"http://localhost\"}", "{\"days\":999999999999}")) {
            JsonNode arguments = MAPPER.readTree(json);
            assertThrows(IllegalArgumentException.class, () -> tools.weather().execute(arguments));
        }
        assertThrows(IllegalArgumentException.class, () -> tools.attractions().execute(MAPPER.createObjectNode().put("limit", -1)));
    }

    @Test
    void rejectsStaleMissingOrInvalidWeather() {
        for (String bad : List.of("{}", "<html>error</html>", weatherFixture().toString().replace("Asia/Shanghai", "UTC"),
                weatherFixture().toString().replace("°C", "°F"), weatherFixture().toString().replace("2026-09-30", "2020-09-30"),
                weatherFixture().toString().replace("[24,24", "[24,null"),
                weatherFixture().toString().replace("[0,0,0,0,0,0,0,0]", "[0]"))) {
            BeijingTravelTools tools = new BeijingTravelTools(MAPPER, uri -> bad, CLOCK);
            assertThrows(IllegalStateException.class, () -> tools.weather().execute(MAPPER.createObjectNode().put("days", 5)), bad);
        }
    }

    @Test
    void rejectsChangedOrUnsafeAttractionPages() {
        for (String bad : List.of("<html>请验证</html>", attractionsFixture().replace("100001406540", "123"),
                attractionsFixture().replace("北京", "上海"), attractionsFixture().replace("https://m.ctrip.com/", "javascript:evil/"),
                attractionsFixture().replace("\"rank\":1", "\"rank\":0"), attractionsFixture().replace("\"4.9\"", "{}"))) {
            BeijingTravelTools tools = new BeijingTravelTools(MAPPER, uri -> bad, CLOCK);
            assertThrows(RuntimeException.class, () -> tools.attractions().execute(MAPPER.createObjectNode().put("limit", 7)), bad);
        }
    }

    @Test
    void preservesMissingRatingRatherThanInventingOne() {
        BeijingTravelTools tools = new BeijingTravelTools(MAPPER,
                uri -> attractionsFixture().replace("\"commentScore\":\"4.9\",", ""), CLOCK);
        JsonNode result = tools.attractions().execute(MAPPER.createObjectNode().put("limit", 7));
        assertTrue(result.at("/attractions/0/rating").isNull());
        assertEquals("20.2w", result.at("/attractions/0/reviewCountDisplay").asText());
    }

    @Test
    void boundsHttpResponsesAndRejectsUpstreamErrorsAndRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/error", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1/private");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/large", exchange -> {
            byte[] bytes = new byte[2 * 1024 * 1024 + 1];
            exchange.sendResponseHeaders(200, bytes.length);
            try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        });
        server.start();
        try {
            TravelHttpClient client = new TravelHttpClient();
            for (String path : List.of("/error", "/redirect", "/large")) {
                assertThrows(IllegalStateException.class, () -> client.get(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path)));
            }
        } finally { server.stop(0); }
    }

    @Test
    void executesNativeModelToolLoopAndEnrichesSourceFacts() {
        List<Prompt> prompts = new ArrayList<>();
        SkillEngine.Run run = engine(tools(), prompt -> { prompts.add(prompt); return scripted(prompt); })
                .execute("beijing-weather-travel", input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals("LIVE_MODEL_MIXED_DATA", run.mode());
        assertEquals(6, run.modelCalls());
        assertEquals(List.of("Skill", "get_user_profile", "get_beijing_weather", "get_beijing_hot_attractions", "get_user_behaviors"),
                run.traces().stream().map(SkillEngine.Trace::tool).toList());
        assertEquals("历史文化、摄影", run.output().path("interests").asText());
        assertEquals("故宫博物院", run.output().at("/recommendations/0/attraction/name").asText());
        assertEquals(run.traces().get(2).output(), run.output().path("weather"));
        assertEquals(run.traces().get(4).output().path("signals"), run.output().at("/profile/behaviors"));
        assertTrue(prompts.get(5).getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                .anyMatch(response -> response.name().equals("get_beijing_hot_attractions") && response.responseData().contains("故宫")));
    }

    @Test
    void requiresUserHistoryBeforeCompletingTravelRecommendations() {
        SkillEngine.Run run = engine(tools(), prompt -> {
            ChatResponse response = scripted(prompt);
            if (response.hasToolCalls() && response.getResult().getOutput().getToolCalls().get(0).name().equals("get_user_behaviors")) {
                return SkillEngineTests.answer(FINAL);
            }
            return response;
        }).execute("beijing-weather-travel", input());
        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("提前结束"));
    }

    @Test
    void rejectsInventedIdsDatesDuplicatesAndInvalidTips() {
        for (String bad : List.of(FINAL.replace("75595", "invented"), FINAL.replace("2026-09-30", "2020-01-01"),
                FINAL.replace("\"recommendations\":[", "\"recommendations\":[{},"), FINAL.replace("\"travelTips\":[", "\"travelTips\":[null,"))) {
            SkillEngine.Run run = engine(tools(), prompt -> {
                ChatResponse response = scripted(prompt);
                return response.hasToolCalls() ? response : SkillEngineTests.answer(bad);
            }).execute("beijing-weather-travel", input());
            assertEquals("FAILED", run.status());
            assertTrue(run.error().contains("北京出游推荐无效"), run.error());
        }
    }

    @Test
    void overwritesModelSuppliedSourceFacts() throws Exception {
        ObjectNode output = (ObjectNode) MAPPER.readTree(FINAL);
        ((ObjectNode) output.at("/recommendations/0")).putObject("attraction").put("name", "伪造景点");
        output.put("interests", "模型伪造兴趣");
        BeijingTravelTools tools = tools();
        Map<String, JsonNode> results = Map.of("get_beijing_weather", tools.weather().execute(MAPPER.createObjectNode().put("days", 5)),
                "get_beijing_hot_attractions", tools.attractions().execute(MAPPER.createObjectNode().put("limit", 7)));
        BeijingTravelValidator.validate(output, results, input());
        assertEquals("故宫博物院", output.at("/recommendations/0/attraction/name").asText());
        assertEquals("历史文化、摄影", output.path("interests").asText());
        assertThrows(IllegalArgumentException.class, () -> BeijingTravelValidator.validate(output, Map.of(), input()));
    }

    @Test
    void externalFailureStopsExecutionWithoutMockFallback() {
        BeijingTravelTools broken = new BeijingTravelTools(MAPPER, uri -> { throw new IllegalStateException("upstream unavailable"); }, CLOCK);
        SkillEngine.Run run = engine(broken, this::scripted).execute("beijing-weather-travel", input());
        assertEquals("FAILED", run.status());
        assertEquals("get_beijing_weather", run.traces().get(2).tool());
        assertEquals("FAILED", run.traces().get(2).status());
        assertTrue(run.output().isEmpty());
        assertEquals(3, run.modelCalls());
    }

    @Test
    void rejectsNonTextOrBlankInterestsBeforeModel() {
        SkillEngine engine = engine(tools(), prompt -> { fail("Model must not be called"); return null; });
        for (ObjectNode input : List.of(input().put("interests", " "), input().put("interests", 3),
                input().put("interests", "a".repeat(513)), input().put("interests", "line\nfeed"))) {
            assertThrows(IllegalArgumentException.class, () -> engine.execute("beijing-weather-travel", input));
        }
    }
}
