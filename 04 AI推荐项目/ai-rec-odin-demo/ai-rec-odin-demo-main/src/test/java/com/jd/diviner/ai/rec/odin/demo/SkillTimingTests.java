package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillDefinition;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillTimingHistory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;

class SkillTimingTests {
    private final SkillDefinition skill = new SkillDefinition("shopping-recommendation", "购物", "推荐", true,
            List.of("username"), "test.md", List.of("get_user_profile", "get_user_behaviors", "search_products", "retrieve_product_knowledge"),
            List.of("get_user_profile", "get_user_behaviors", "search_products", "retrieve_product_knowledge"), List.of("products"), 8, 12);

    private SkillTimingHistory.Plan plan(SkillTimingHistory history) {
        return history.plan(skill, "test", "instructions", true);
    }

    private SkillTimingHistory.Report sample(SkillTimingHistory.Plan plan, long milliseconds) {
        var clock = new AtomicLong();
        var tracker = new SkillTimingHistory.Tracker(plan, 0, clock::get);
        tracker.enter("synthesis");
        clock.set(milliseconds * 1_000_000);
        return tracker.report(true);
    }

    @Test
    void separatesRealAndDemoTimingSamples() {
        var history = new SkillTimingHistory(() -> true);
        var demo = history.plan(skill, "test", "instructions", true, "DEMO");
        history.record("demo-run", demo, sample(demo, 1000), true);
        assertEquals(0, plan(history).estimate().sampleCount());
        assertEquals(1, history.plan(skill, "test", "instructions", true, "DEMO").estimate().sampleCount());
    }

    @Test
    void localCompletionOmitsFinalModelStageAndKeepsIndependentHistory() {
        var history = new SkillTimingHistory(() -> true);
        var normal = history.plan(skill, "test", "instructions", true, "REAL", false);
        var local = history.plan(skill, "test", "instructions", true, "REAL", true);

        assertTrue(normal.estimate().stages().stream().anyMatch(stage -> stage.id().equals("synthesis")));
        assertTrue(normal.estimate().stages().stream().anyMatch(stage -> stage.id().equals("planning")));
        assertFalse(local.estimate().stages().stream().anyMatch(stage -> stage.id().equals("synthesis")));
        assertFalse(local.estimate().stages().stream().anyMatch(stage -> stage.id().equals("planning")));
        history.record("local", local, sample(local, 1000), true);
        assertEquals(1, history.plan(skill, "test", "instructions", true, "REAL", true)
                .estimate().sampleCount());
        assertEquals(0, history.plan(skill, "test", "instructions", true, "REAL", false)
                .estimate().sampleCount());
    }

    @Test
    void measuresRepeatedStagesAndAccountsForEveryMillisecond() {
        var clock = new AtomicLong();
        var tracker = new SkillTimingHistory.Tracker(plan(new SkillTimingHistory(() -> true)), 0, clock::get);
        tracker.enter("planning");
        clock.set(10_000_000);
        tracker.enter("user");
        clock.set(30_000_000);
        tracker.leave();
        tracker.completed("user");
        clock.set(35_000_000);
        tracker.enter("planning");
        clock.set(50_000_000);
        var snapshot = tracker.snapshot(true);
        var planning = snapshot.stages().stream().filter(stage -> stage.id().equals("planning")).findFirst().orElseThrow();
        assertEquals(25, planning.durationMs());
        assertEquals(15, planning.activeElapsedMs());
        assertEquals(2, planning.executions());
        assertTrue(planning.active());
        assertNotNull(snapshot.estimate());
        assertNull(tracker.snapshot(false).estimate());
        var report = tracker.report(false);
        assertEquals(50, report.elapsedMs());
        assertEquals(report.elapsedMs(), report.stages().stream().mapToLong(SkillTimingHistory.StageMeasurement::durationMs).sum());
        assertFalse(report.stages().stream().anyMatch(SkillTimingHistory.StageMeasurement::active));
        assertTrue(report.stages().stream().filter(stage -> stage.id().equals("user")).findFirst().orElseThrow().complete());
        assertFalse(report.stages().stream().filter(stage -> stage.id().equals("planning")).findFirst().orElseThrow().complete());
    }

    @Test
    void learnsOnlySuccessfulUniqueRunsAndUsesRobustRollingHistory() {
        var history = new SkillTimingHistory(() -> true);
        var initial = plan(history);
        assertEquals("INITIAL", initial.estimate().basis());
        assertEquals(0, initial.estimate().sampleCount());
        history.record("failed", initial, sample(initial, 500), false);
        assertEquals(0, plan(history).estimate().sampleCount());
        history.record("first", initial, sample(initial, 10000), true);
        history.record("first", initial, sample(initial, 10000), true);
        assertEquals(1, plan(history).estimate().sampleCount());
        assertEquals("BLENDED", plan(history).estimate().basis());
        assertTrue(plan(history).estimate().expectedMs() < initial.estimate().expectedMs());
        for (int index = 0; index < 4; index++) history.record("normal-" + index, initial, sample(initial, 10000), true);
        history.record("outlier", initial, sample(initial, 200000), true);
        var estimate = plan(history).estimate();
        assertEquals("HISTORY", estimate.basis());
        assertEquals(6, estimate.sampleCount());
        assertTrue(Math.abs(estimate.expectedMs() - 10000) < 20);
        assertTrue(estimate.lowerMs() < estimate.expectedMs());
        assertTrue(estimate.upperMs() >= 200000);
        assertEquals(estimate.expectedMs(), estimate.stages().stream().mapToLong(SkillTimingHistory.StageEstimate::expectedMs).sum());
        for (int index = 0; index < 35; index++) history.record("new-" + index, initial, sample(initial, 20000), true);
        assertEquals(30, plan(history).estimate().sampleCount());
        assertTrue(Math.abs(plan(history).estimate().expectedMs() - 20000) < 20);
    }

    @Test
    void isolatesModelsInstructionsModesConfigurationAndIndexReadiness() {
        var ready = new AtomicBoolean(false);
        var history = new SkillTimingHistory(ready::get);
        var cold = plan(history);
        assertTrue(cold.estimate().coldStart());
        history.record("cold", cold, sample(cold, 10000), true);
        assertEquals(1, plan(history).estimate().sampleCount());
        assertEquals(0, history.plan(skill, "other", "instructions", true).estimate().sampleCount());
        assertEquals(0, history.plan(skill, "test", "updated", true).estimate().sampleCount());
        assertEquals(0, history.plan(skill, "test", "instructions", false).estimate().sampleCount());
        var changed = new SkillDefinition(skill.id(), skill.name(), skill.description(), true, skill.requiredInputs(), skill.instructions(),
                skill.tools(), skill.requiredTools(), skill.requiredOutputs(), 10, 20);
        assertEquals(0, history.plan(changed, "test", "instructions", true).estimate().sampleCount());
        ready.set(true);
        var warm = plan(history);
        assertFalse(warm.estimate().coldStart());
        assertEquals(0, warm.estimate().sampleCount());
        assertTrue(warm.estimate().expectedMs() < cold.estimate().expectedMs());
        for (int index = 0; index < 65; index++) {
            var other = history.plan(skill, "model-" + index, "instructions", true);
            history.record("run-" + index, other, sample(other, 10000), true);
        }
        ready.set(false);
        assertEquals(0, plan(history).estimate().sampleCount());
    }

    @Test
    void engineStreamsActualTimingAndLearnsWithoutCallingModelForEstimates() {
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Must stream"); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                calls.incrementAndGet();
                var response = SkillEngineTests.scriptedResponse(prompt);
                var chunk = new ChatResponse(List.of(new Generation(response.getResult().getOutput(),
                        ChatGenerationMetadata.builder().finishReason(response.hasToolCalls() ? "tool_calls" : "stop").build())),
                        response.getMetadata());
                return Flux.just(chunk).delayElements(Duration.ofMillis(2));
            }
        };
        var registry = SkillEngineTests.registry();
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, SkillEngineTests.agentCatalogResource(), registry);
        var history = new SkillTimingHistory(() -> true);
        var engine = new SkillEngine(SkillEngineTests.MAPPER, catalog, registry, new SkillModelClient(model, "test"), history);
        assertEquals(0, engine.estimate(skill.id()).sampleCount());
        assertEquals(0, calls.get());
        var events = new CopyOnWriteArrayList<SkillEngine.Progress>();
        var run = engine.executeStreaming(skill.id(), SkillProgressTests.input(), events::add, delta -> {}, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertNotNull(events.get(0).timing().estimate());
        assertTrue(events.stream().skip(1).allMatch(event -> event.timing().estimate() == null));
        assertEquals(run.durationMs(), run.timing().elapsedMs());
        assertEquals(run.durationMs(), run.timing().stages().stream().mapToLong(SkillTimingHistory.StageMeasurement::durationMs).sum());
        assertTrue(run.timing().stages().stream().allMatch(stage -> !stage.active()));
        assertEquals(2, run.timing().stages().stream().filter(stage -> stage.id().equals("user")).findFirst().orElseThrow().executions());
        assertEquals(1, engine.estimate(skill.id()).sampleCount());
        assertEquals(0, run.timing().estimate().sampleCount());
        assertEquals(run.modelCalls(), calls.get());
        assertThrows(SkillEngine.UnknownSkillException.class, () -> engine.estimate("unknown"));
    }
}
