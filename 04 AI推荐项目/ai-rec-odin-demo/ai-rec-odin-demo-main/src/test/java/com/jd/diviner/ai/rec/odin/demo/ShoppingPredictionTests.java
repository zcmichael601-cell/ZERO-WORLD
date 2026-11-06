package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation.ShoppingPredictionValidator;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import com.jd.diviner.ai.rec.odin.demo.web.controller.SkillController;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ShoppingPredictionTests {

    private static final String FINAL = """
            {"summary":"根据画像与行为预测，并非确定结论",
             "predictions":[{"action":"compare","category":"跑鞋","description":"可能继续对比缓震跑鞋",
               "timeWindow":"未来几天","confidence":78,"reasoning":"近期多次浏览相关品类",
               "alternative":"可能仍在观望，不会立即下单",
               "evidence":[{"source":"get_user_behaviors","path":"/signals/0"}]}],
             "preferences":[{"dimension":"预算约束","preference":"单件价格不超过800元",
               "confidence":84,"reasoning":"参考画像中的单件预算，不推断收入",
               "evidence":[{"source":"get_user_profile","path":"/budget"}]}],
             "uncertainties":["缺少加购及结算信号，无法确认购买意向"]}
            """;
    private ToolRegistry registry;
    private SkillCatalog catalog;
    private Map<String, JsonNode> toolResults;

    @BeforeEach
    void setUp() {
        registry = SkillEngineTests.registry();
        catalog = new SkillCatalog(SkillEngineTests.MAPPER, new ClassPathResource("skills/recommendation.json"), registry);
        toolResults = Map.of("get_user_profile", registry.require("get_user_profile").execute(input()),
                "get_user_behaviors", registry.require("get_user_behaviors").execute(input()));
    }

    @Test
    void runsSkillAndBothDataToolsBeforeReturningModelPredictions() {
        SkillEngine.Run run = engine(prompt -> scriptedResponse(prompt, FINAL))
                .execute("shopping-behavior-prediction", input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(4, run.modelCalls());
        assertEquals(List.of("Skill", "get_user_profile", "get_user_behaviors"), run.traces().stream().map(SkillEngine.Trace::tool).toList());
        assertEquals("compare", run.output().path("predictions").get(0).path("action").asText());
        assertEquals(78, run.output().path("predictions").get(0).path("confidence").asInt());
        assertEquals("近 7 天浏览跑鞋 8 次", run.output().path("predictions").get(0).path("evidence").get(0).path("value").asText());
        assertEquals(800, run.output().path("preferences").get(0).path("evidence").get(0).path("value").asInt());
        assertFalse(run.output().has("products"));
        assertFalse(run.output().has("needs"));
    }

    @Test
    void exposesPredictionsAndGroundedEvidenceOverHttp() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new SkillController(catalog,
                engine(prompt -> scriptedResponse(prompt, FINAL)), registry)).build();
        mvc.perform(post("/api/skills/shopping-behavior-prediction/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"username\":\"xiaoming\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.output.predictions[0].action").value("compare"))
                .andExpect(jsonPath("$.data.output.preferences[0].evidence[0].value").value(800))
                .andExpect(jsonPath("$.data.output.products").doesNotExist());
    }

    @Test
    void replacesModelSuppliedEvidenceValueWithActualToolData() throws Exception {
        ObjectNode output = (ObjectNode) SkillEngineTests.MAPPER.readTree(FINAL);
        ((ObjectNode) output.path("preferences").get(0).path("evidence").get(0)).put("value", "虚构的预算");
        ShoppingPredictionValidator.validate(output, toolResults);
        assertEquals(800, output.path("preferences").get(0).path("evidence").get(0).path("value").asInt());
    }

    @Test
    void rejectsInventedOrUnapprovedEvidenceReferences() throws Exception {
        for (String invalid : List.of(FINAL.replace("/signals/0", "/signals/99"),
                FINAL.replace("/budget", "/username"), FINAL.replace("/budget", "/city"),
                FINAL.replace("get_user_behaviors", "search_products"), FINAL.replace("/signals/0", "/signals/-1"))) {
            JsonNode output = SkillEngineTests.MAPPER.readTree(invalid);
            assertThrows(IllegalArgumentException.class, () -> ShoppingPredictionValidator.validate(output, toolResults));
        }
    }

    @Test
    void rejectsInvalidActionsScoresAndMissingUncertainty() throws Exception {
        for (String invalid : List.of(FINAL.replace("\"compare\"", "\"place_order_now\""),
                FINAL.replace("\"confidence\":78", "\"confidence\":101"),
                FINAL.replace("\"confidence\":78", "\"confidence\":0.8"),
                FINAL.replace("\"confidence\":78", "\"confidence\":999999999999999999999"),
                FINAL.replace("[\"缺少加购及结算信号，无法确认购买意向\"]", "[]"))) {
            JsonNode output = SkillEngineTests.MAPPER.readTree(invalid);
            assertThrows(IllegalArgumentException.class, () -> ShoppingPredictionValidator.validate(output, toolResults));
        }
    }

    @Test
    void acceptsNoPredictionWhenDataIsInsufficient() {
        SkillEngine.Run run = engine(prompt -> scriptedResponse(prompt,
                "{\"summary\":\"当前信息不足，无法做可靠预测\",\"predictions\":[],\"preferences\":[],\"uncertainties\":[\"缺少足够证据\"]}"))
                .execute("shopping-behavior-prediction", input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertTrue(run.output().path("predictions").isEmpty());
    }

    @Test
    void rejectsInvalidModelOutputWithoutFallingBack() {
        SkillEngine.Run run = engine(prompt -> scriptedResponse(prompt, FINAL.replace("/signals/0", "/signals/99")))
                .execute("shopping-behavior-prediction", input());
        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("证据不存在"));
        assertTrue(run.output().isEmpty());
        assertEquals(3, run.traces().size());
    }

    @Test
    void cannotCallProductToolOrFinishBeforeReadingUserData() {
        SkillEngine.Run unauthorized = engine(prompt -> prompt.getInstructions().size() == 2
                ? scriptedResponse(prompt, FINAL) : SkillEngineTests.tool("products", "search_products", "{}"))
                .execute("shopping-behavior-prediction", input());
        assertEquals("FAILED", unauthorized.status());
        assertTrue(unauthorized.error().contains("未授权"));
        SkillEngine.Run premature = engine(prompt -> SkillEngineTests.answer(FINAL))
                .execute("shopping-behavior-prediction", input());
        assertEquals("FAILED", premature.status());
        assertTrue(premature.traces().isEmpty());
    }

    private ChatResponse scriptedResponse(Prompt prompt, String result) {
        long count = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();
        return switch ((int) count) {
            case 0 -> SkillEngineTests.tool("prediction-skill", "Skill", "{\"command\":\"shopping-behavior-prediction\"}");
            case 1 -> SkillEngineTests.tool("prediction-profile", "get_user_profile", "{\"username\":\"xiaoming\"}");
            case 2 -> SkillEngineTests.tool("prediction-behaviors", "get_user_behaviors", "{\"username\":\"xiaoming\"}");
            default -> SkillEngineTests.answer(result);
        };
    }

    private SkillEngine engine(ChatModel model) {
        return new SkillEngine(SkillEngineTests.MAPPER, catalog, registry, new SkillModelClient(model, "test-model"));
    }

    private JsonNode input() {
        return SkillEngineTests.MAPPER.valueToTree(Map.of("username", "xiaoming"));
    }
}
