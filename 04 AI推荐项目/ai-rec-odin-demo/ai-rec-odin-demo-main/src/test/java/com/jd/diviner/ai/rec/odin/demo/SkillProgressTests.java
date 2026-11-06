package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class SkillProgressTests {

    static SkillEngine engine(ChatModel model) {
        var registry = SkillEngineTests.registry();
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, SkillEngineTests.agentCatalogResource(), registry);
        return new SkillEngine(SkillEngineTests.MAPPER, catalog, registry, new SkillModelClient(model, "test-model"));
    }

    static JsonNode input() {
        return SkillEngineTests.MAPPER.createObjectNode().put("username", "xiaoming");
    }

    @Test
    void emitsProgressAtActualModelAndToolBoundaries() {
        List<SkillEngine.Progress> events = new ArrayList<>();
        SkillEngine engine = engine(prompt -> {
            assertEquals("MODEL_STARTED", events.get(events.size() - 1).type());
            return SkillEngineTests.scriptedResponse(prompt);
        });
        SkillEngine.Run run = engine.execute("shopping-recommendation", input(), events::add, () -> false);
        assertEquals("SUCCESS", run.status());
        assertEquals("RUN_STARTED", events.get(0).type());
        assertEquals("RUN_COMPLETED", events.get(events.size() - 1).type());
        assertEquals(6, events.stream().filter(event -> event.type().equals("MODEL_STARTED")).count());
        assertEquals(6, events.stream().filter(event -> event.type().equals("MODEL_COMPLETED")).count());
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1, events.get(index).sequence());
            assertEquals(run.runId(), events.get(index).runId());
            assertEquals("shopping-recommendation", events.get(index).skillId());
            if (index > 0) assertTrue(events.get(index).elapsedMs() >= events.get(index - 1).elapsedMs());
        }
        for (SkillEngine.Trace trace : run.traces()) {
            var toolEvents = events.stream().filter(event -> trace.id().equals(event.toolCallId())).toList();
            assertEquals(List.of("TOOL_STARTED", "TOOL_COMPLETED"), toolEvents.stream().map(SkillEngine.Progress::type).toList());
            assertTrue(toolEvents.stream().allMatch(event -> trace.tool().equals(event.tool()) && trace.modelRound() == event.modelRound()));
        }
        assertEquals("VALIDATION_STARTED", events.get(events.size() - 2).type());
    }

    @Test
    void reportsModelFailureWithoutFabricatedCompletionOrSecrets() {
        List<SkillEngine.Progress> events = new ArrayList<>();
        SkillEngine.Run run = engine(prompt -> { throw new IllegalStateException("Bearer private-secret"); })
                .execute("shopping-recommendation", input(), events::add, () -> false);
        assertEquals("FAILED", run.status());
        assertEquals(List.of("RUN_STARTED", "MODEL_STARTED", "MODEL_FAILED", "RUN_FAILED"),
                events.stream().map(SkillEngine.Progress::type).toList());
        assertFalse(events.toString().contains("private-secret"));
    }

    @Test
    void reportsToolFailureWithoutClaimingItCompleted() {
        List<SkillEngine.Progress> events = new ArrayList<>();
        SkillEngine.Run run = engine(prompt -> prompt.getInstructions().size() == 2 ? SkillEngineTests.scriptedResponse(prompt)
                : SkillEngineTests.tool("bad-user", "get_user_profile", "{\"username\":\"xiaoyu\"}"))
                .execute("shopping-recommendation", input(), events::add, () -> false);
        assertEquals("FAILED", run.status());
        assertEquals(List.of("TOOL_STARTED", "TOOL_FAILED"), events.stream()
                .filter(event -> "bad-user".equals(event.toolCallId())).map(SkillEngine.Progress::type).toList());
        assertEquals("RUN_FAILED", events.get(events.size() - 1).type());
        assertFalse(events.stream().anyMatch(event -> event.type().equals("RUN_COMPLETED")));
    }

    @Test
    void invalidFinalOutputFailsAfterValidationRatherThanReportingSuccess() {
        List<SkillEngine.Progress> events = new ArrayList<>();
        SkillEngine.Run run = engine(prompt -> {
            if ("FINAL_ANSWER".equals(StandardSkillTests.facts(prompt).path("phase").asText())) {
                return SkillEngineTests.answer("{}");
            }
            var response = SkillEngineTests.scriptedResponse(prompt);
            return response.hasToolCalls() ? response : SkillEngineTests.answer("{}");
        }).execute("shopping-recommendation", input(), events::add, () -> false);
        assertEquals("FAILED", run.status());
        assertEquals("VALIDATION_STARTED", events.get(events.size() - 2).type());
        assertEquals("RUN_FAILED", events.get(events.size() - 1).type());
    }

    @Test
    void cancellationPreventsSubsequentModelAndToolCalls() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        List<SkillEngine.Progress> events = new ArrayList<>();
        SkillEngine engine = engine(prompt -> { calls.incrementAndGet(); return SkillEngineTests.scriptedResponse(prompt); });
        assertThrows(CancellationException.class, () -> engine.execute("shopping-recommendation", input(), event -> {
            events.add(event);
            if (event.type().equals("TOOL_COMPLETED")) cancelled.set(true);
        }, cancelled::get));
        assertEquals(1, calls.get());
        assertFalse(events.stream().anyMatch(event -> "get_user_profile".equals(event.tool())));
        assertFalse(events.stream().anyMatch(event -> event.type().equals("RUN_COMPLETED")));
    }

    @Test
    void concurrentRunsKeepProgressAndSequencesIsolated() {
        SkillEngine engine = engine(SkillEngineTests::scriptedResponse);
        var runs = java.util.stream.IntStream.range(0, 3).mapToObj(index -> CompletableFuture.supplyAsync(() -> {
            List<SkillEngine.Progress> events = new ArrayList<>();
            SkillEngine.Run run = engine.execute("shopping-recommendation", input(), events::add, () -> false);
            assertTrue(events.stream().allMatch(event -> event.runId().equals(run.runId())));
            assertEquals(1, events.get(0).sequence());
            assertEquals(events.size(), events.get(events.size() - 1).sequence());
            return run.runId();
        })).toList();
        assertEquals(3, runs.stream().map(CompletableFuture::join).distinct().count());
    }
}
