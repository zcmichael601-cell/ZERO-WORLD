package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import com.jd.diviner.ai.rec.odin.demo.web.controller.SkillController;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SkillControllerTests {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry registry = SkillEngineTests.registry();
        SkillCatalog catalog = new SkillCatalog(mapper, new ClassPathResource("skills/recommendation.json"), registry);
        mvc = MockMvcBuilders.standaloneSetup(new SkillController(catalog, new SkillEngine(mapper, catalog, registry,
                new SkillModelClient(SkillEngineTests::scriptedResponse, "test-model")), registry)).build();
    }

    @Test
    void exposesInitialTimingEstimateAndRejectsUnknownSkill() throws Exception {
        mvc.perform(get("/api/skills/shopping-recommendation/estimate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skillId").value("shopping-recommendation"))
                .andExpect(jsonPath("$.data.basis").value("INITIAL"))
                .andExpect(jsonPath("$.data.sampleCount").value(0))
                .andExpect(jsonPath("$.data.expectedMs").isNumber())
                .andExpect(jsonPath("$.data.stages").isArray());
        mvc.perform(get("/api/skills/missing/estimate")).andExpect(status().isNotFound());
        mvc.perform(get("/api/skills/shopping-recommendation-fast/estimate?userDataMode=DEMO"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.skillId").value("shopping-recommendation-fast"))
                .andExpect(jsonPath("$.data.expectedMs").value(5000));
    }

    @Test
    void listsEnabledSkillsAndRegisteredTools() throws Exception {
        mvc.perform(get("/api/skills")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(7));
        mvc.perform(get("/api/skills/tools")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(10));
    }

    @Test
    void executesRecommendationOverHttp() throws Exception {
        mvc.perform(post("/api/skills/shopping-recommendation/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"username\":\"xiaoming\"}}"))
                .andExpect(status().isOk()).andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.traces.length()").value(5))
                .andExpect(jsonPath("$.data.modelCalls").value(6))
                .andExpect(jsonPath("$.data.output.products.length()").value(1));
    }

    @Test
    void listsConfiguredMemoryPinsWithoutInventedProfileData() throws Exception {
        mvc.perform(get("/api/skills/users")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].username").value("315c70c8f4a25681"))
                .andExpect(jsonPath("$.data[0].source").value("AI_MEMORY"))
                .andExpect(jsonPath("$.data[0].interests").doesNotExist())
                .andExpect(jsonPath("$.data[0].budget").doesNotExist());
    }

    @Test
    void trimsPinWithoutMappingItToAnUnrelatedUserAlias() throws Exception {
        mvc.perform(post("/api/skills/shopping-recommendation/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"username\":\" xiaoming \"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.output.profile.username").value("xiaoming"));
        mvc.perform(post("/api/skills/shopping-recommendation/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"username\":\"小明\"}}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    @Test
    void rejectsInvalidInputAndMalformedJson() throws Exception {
        for (String body : List.of("{}", "null", "[]", "{", "{\"input\":{\"username\":123}}", "{\"input\":{\"username\":\"  \"}}",
                "{\"input\":{\"username\":\"line\\nfeed\"}}", "{\"input\":{\"username\":\"" + "a".repeat(65) + "\"}}")) {
            mvc.perform(post("/api/skills/user-insight/run").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
    }

    @Test
    void returnsNotFoundForUnknownSkill() throws Exception {
        mvc.perform(post("/api/skills/missing/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"username\":\"xiaoming\"}}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void exposesModelMetadataAndInstructionsButNoCredentials() throws Exception {
        mvc.perform(get("/api/skills/runtime")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model").value("test-model"))
                .andExpect(jsonPath("$.data.runTimeoutMs").value("1200000"))
                .andExpect(jsonPath("$.data.clientTimeoutMs").value("1290000"))
                .andExpect(jsonPath("$.data.mode").value("LIVE_MODEL_MIXED_DATA"));
        mvc.perform(get("/api/skills/shopping-recommendation/instructions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isString());
        mvc.perform(get("/api/skills/missing/instructions")).andExpect(status().isNotFound());
    }
}
