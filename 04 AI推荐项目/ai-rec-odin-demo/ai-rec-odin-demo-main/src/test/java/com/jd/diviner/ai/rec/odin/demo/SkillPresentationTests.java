package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.fixtures.MockRecommendationTools;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillDataView;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class SkillPresentationTests {

    @Test
    void businessViewOmitsImplementationMetadataWithoutMutatingInternalData() {
        JsonNode raw = MockRecommendationTools.profile(SkillEngineTests.MAPPER).execute(SkillProgressTests.input());
        ObjectNode business = (ObjectNode) SkillDataView.businessData(raw);
        assertFalse(business.has("source"));
        assertFalse(business.has("notice"));
        assertEquals(raw.path("interests"), business.path("interests"));
        assertEquals(raw.path("budget"), business.path("budget"));
        business.put("username", "changed");
        assertEquals("xiaoming", raw.path("username").asText());
        assertEquals("MOCK", raw.path("source").asText());
        assertTrue(raw.has("notice"));
    }

    @Test
    void preservesRealSourcesAndEvidenceReferences() throws Exception {
        JsonNode real = SkillEngineTests.MAPPER.readTree("""
                {"source":{"provider":"Open-Meteo","dataMode":"LIVE","url":"https://api.open-meteo.com/v1/forecast"},
                 "notice":"城区预报不代表山区天气","evidence":{"source":"get_user_profile","path":"/interests"}}
                """);
        assertEquals(real, SkillDataView.businessData(real));
    }

    @Test
    void modelReceivesBusinessResultsWhileInternalTracesRetainProvenance() {
        List<SkillEngine.Progress> events = new ArrayList<>();
        var engine = SkillProgressTests.engine(prompt -> {
            for (var message : prompt.getInstructions()) {
                assertFalse(String.valueOf(message.getText()).toLowerCase(Locale.ROOT).contains("mock"));
                if (message instanceof ToolResponseMessage toolMessage) {
                    for (var response : toolMessage.getResponses()) {
                        assertFalse(response.responseData().toLowerCase(Locale.ROOT).contains("mock"));
                    }
                }
            }
            return SkillEngineTests.scriptedResponse(prompt);
        });
        SkillEngine.Run run = engine.execute("shopping-recommendation", SkillProgressTests.input(), events::add, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals("MOCK", run.traces().get(1).output().path("source").asText());
        assertEquals("LIVE_MODEL_MIXED_DATA", run.mode());
        assertFalse(run.output().toString().toLowerCase(Locale.ROOT).contains("mock"));
        assertTrue(events.stream().noneMatch(event -> event.message().toLowerCase(Locale.ROOT).contains("mock")));
        assertEquals("SKU-001", run.output().at("/products/0/productId").asText());
    }

    @Test
    void publishedSkillInstructionsAndToolDescriptionsUseBusinessLanguage() {
        var registry = SkillEngineTests.registry();
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, SkillEngineTests.agentCatalogResource(), registry);
        for (var skill : catalog.enabledSkills()) {
            assertFalse(skill.description().toLowerCase(Locale.ROOT).contains("mock"));
            assertFalse(catalog.instructions(skill.id()).toLowerCase(Locale.ROOT).contains("mock"));
            for (String tool : skill.tools()) {
                assertFalse(registry.require(tool).description().toLowerCase(Locale.ROOT).contains("mock"));
            }
        }
    }

    @Test
    void productIdentifiersDoNotExposeImplementationType() throws Exception {
        JsonNode products = MockRecommendationTools.products(SkillEngineTests.MAPPER).execute(SkillEngineTests.MAPPER.readTree("""
                {"interests":["跑步","户外","健康","数码","办公","音频","家居","咖啡","厨房"],"budget":1500,"limit":12}
                """));
        assertEquals(12, products.path("products").size());
        for (JsonNode product : products.path("products")) assertTrue(product.path("id").asText().startsWith("SKU-"));
        assertEquals("MOCK", products.path("source").asText());
        assertFalse(SkillDataView.businessData(products).toString().contains("MOCK"));
    }
}
