package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.fixtures.MockRecommendationTools;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class MockUserTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void selectableUsersMatchToolDataAndHaveDistinctInterestsAndBehaviors() {
        var interests = new HashSet<JsonNode>();
        var travelInterests = new HashSet<JsonNode>();
        var behaviors = new HashSet<JsonNode>();
        for (var user : MockRecommendationTools.users()) {
            JsonNode input = MAPPER.createObjectNode().put("username", user.username());
            JsonNode profile = MockRecommendationTools.profile(MAPPER).execute(input);
            JsonNode history = MockRecommendationTools.behaviors(MAPPER).execute(input);
            assertEquals(user.username(), profile.path("username").asText());
            assertEquals(user.username(), history.path("username").asText());
            assertEquals(MAPPER.valueToTree(user.interests()), profile.path("interests"));
            assertEquals(MAPPER.valueToTree(user.travelInterests()), profile.path("travelInterests"));
            assertEquals(MAPPER.valueToTree(user.behaviors()), history.path("signals"));
            assertEquals("MOCK", profile.path("source").asText());
            assertEquals("MOCK", history.path("source").asText());
            assertTrue(interests.add(profile.path("interests")));
            assertTrue(travelInterests.add(profile.path("travelInterests")));
            assertTrue(behaviors.add(history.path("signals")));
        }
    }

    @Test
    void rejectsUnknownUsersRatherThanReusingAnotherPersona() {
        for (String username : List.of("unknown", " ", "xiaoming\n", "a".repeat(65))) {
            JsonNode input = MAPPER.createObjectNode().put("username", username);
            assertThrows(IllegalArgumentException.class, () -> MockRecommendationTools.profile(MAPPER).execute(input));
            assertThrows(IllegalArgumentException.class, () -> MockRecommendationTools.behaviors(MAPPER).execute(input));
        }
    }

    @Test
    void aliasesResolveToSameCanonicalUserAndToolOutputsAreIsolated() {
        assertEquals("xiaoming", MockRecommendationTools.requireUser(" XIAOMING ").username());
        assertEquals("xiaoyu", MockRecommendationTools.requireUser("小雨").username());
        assertEquals("laowang", MockRecommendationTools.requireUser("老王").username());
        JsonNode input = MAPPER.createObjectNode().put("username", "xiaoming");
        ObjectNode first = (ObjectNode) MockRecommendationTools.profile(MAPPER).execute(input);
        first.putArray("interests").add("被修改的数据");
        assertEquals("跑步", MockRecommendationTools.profile(MAPPER).execute(input).at("/interests/0").asText());
        assertThrows(UnsupportedOperationException.class, () -> MockRecommendationTools.users().clear());
    }

    @Test
    void modelToolLoopUsesSelectedUsersDataWithoutMixingUsers() {
        var registry = SkillEngineTests.registry();
        var catalog = new SkillCatalog(MAPPER, new ClassPathResource("skills/recommendation.json"), registry);
        for (var user : MockRecommendationTools.users()) {
            JsonNode input = MAPPER.createObjectNode().put("username", user.username());
            SkillEngine engine = new SkillEngine(MAPPER, catalog, registry, new SkillModelClient(prompt -> {
                long results = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();
                return switch ((int) results) {
                    case 0 -> SkillEngineTests.tool("load", "Skill", "{\"command\":\"user-insight\"}");
                    case 1 -> SkillEngineTests.tool("profile", "get_user_profile", input.toString());
                    case 2 -> SkillEngineTests.tool("history", "get_user_behaviors", input.toString());
                    default -> SkillEngineTests.answer("{\"summary\":\"依据该用户的样本进行分析\",\"needs\":[]}");
                };
            }, "test-model"));
            SkillEngine.Run run = engine.execute("user-insight", input);
            assertEquals("SUCCESS", run.status(), run.error());
            assertEquals(user.username(), run.output().at("/profile/username").asText());
            assertEquals(MAPPER.valueToTree(user.behaviors()), run.output().at("/profile/behaviors"));
            assertEquals(MAPPER.valueToTree(user.interests()), run.output().at("/profile/interests"));
        }
    }
}
