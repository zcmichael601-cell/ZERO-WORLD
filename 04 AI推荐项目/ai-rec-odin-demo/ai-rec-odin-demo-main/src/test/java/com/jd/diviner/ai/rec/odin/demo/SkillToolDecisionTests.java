package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillToolDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.*;

class SkillToolDecisionTests {
    private final ToolCallback callback = new ToolCallback() {
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("get_user_profile").description("Profile lookup").inputSchema("{\"type\":\"object\"}").build();
        }
        public String call(String arguments) { throw new AssertionError("Decoding must never execute a tool"); }
    };

    private ChatResponse response(String text, String finish) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder().finishReason(finish).build())));
    }

    @Test
    void normalizesOnlyExplicitDecisionIntoAValidatedToolRequest() {
        var raw = response("{\"name\":\"get_user_profile\",\"arguments\":{\"username\":\"xiaoming\"}}", "stop");
        var result = SkillToolDecision.decode(SkillEngineTests.MAPPER, raw, List.of(callback), Map.of());
        assertTrue(result.hasToolCalls());
        assertEquals("", result.getResult().getOutput().getText());
        var call = result.getResult().getOutput().getToolCalls().get(0);
        assertEquals("get_user_profile", call.name());
        assertEquals("{\"username\":\"xiaoming\"}", call.arguments());
        assertTrue(call.id().startsWith("decision_"));
        assertSame(raw.getMetadata(), result.getMetadata());
        assertThrows(SkillToolDecision.InvalidDecision.class, () -> SkillToolDecision.decode(SkillEngineTests.MAPPER, raw,
                List.of(callback), Map.of("get_user_profile", SkillEngineTests.MAPPER.createObjectNode())));
        assertThrows(SkillToolDecision.InvalidDecision.class, () -> SkillToolDecision.decode(SkillEngineTests.MAPPER,
                response(raw.getResult().getOutput().getText(), "length"), List.of(callback), Map.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<tool_call><function=get_user_profile></function></tool_call>",
            "```json\n{\"name\":\"get_user_profile\",\"arguments\":{}}\n```",
            "{\"name\":\"get_user_profile\",\"arguments\":{}} {}",
            "{\"name\":\"get_user_profile\",\"name\":\"execute_shell\",\"arguments\":{}}",
            "{\"name\":\"get_user_profile\",\"arguments\":\"{}\"}",
            "{\"name\":\"get_user_profile\",\"arguments\":{},\"extra\":true}",
            "{\"name\":\"execute_shell\",\"arguments\":{}}",
            "[]", "null", "{}"
    })
    void rejectsMarkupUnknownToolsAmbiguousJsonAndExtraFields(String text) {
        assertThrows(SkillToolDecision.InvalidDecision.class, () -> SkillToolDecision.decode(SkillEngineTests.MAPPER,
                response(text, "stop"), List.of(callback), Map.of()));
    }
}
