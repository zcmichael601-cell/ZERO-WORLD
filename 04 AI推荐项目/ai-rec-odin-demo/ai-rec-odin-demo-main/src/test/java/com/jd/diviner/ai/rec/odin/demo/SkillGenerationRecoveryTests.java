package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

class SkillGenerationRecoveryTests {
    private SkillEngine engine(ChatModel model) {
        var registry = SkillEngineTests.registry();
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, SkillEngineTests.agentCatalogResource(), registry);
        return new SkillEngine(SkillEngineTests.MAPPER, catalog, registry, new SkillModelClient(model, "test"));
    }

    private ChatModel model(Function<Prompt, ChatResponse> generate) {
        return new ChatModel() {
            public ChatResponse call(Prompt prompt) { return generate.apply(prompt); }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(generate.apply(prompt))); }
        };
    }

    private ChatResponse completed(ChatResponse response) {
        return new ChatResponse(List.of(new Generation(response.getResult().getOutput(),
                ChatGenerationMetadata.builder().finishReason(response.hasToolCalls() ? "tool_calls" : "stop").build())));
    }

    private ChatResponse withFinish(ChatResponse response, String finish) {
        return new ChatResponse(List.of(new Generation(response.getResult().getOutput(),
                ChatGenerationMetadata.builder().finishReason(finish).build())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void lengthRecoveryKeepsFactsAndNeverPublishesTruncatedAnswer(boolean streaming) {
        var calls = new AtomicInteger();
        var events = new ArrayList<SkillEngine.Progress>();
        var runtime = engine(model(prompt -> {
            int round = calls.incrementAndGet();
            if (round < 6) return completed(SkillEngineTests.scriptedResponse(prompt));
            if (round == 6) return withFinish(SkillEngineTests.answer("{\"summary\":\"incomplete"), "length");
            var facts = StandardSkillTests.facts(prompt);
            assertEquals("FINAL_ANSWER", facts.path("phase").asText());
            assertEquals("LENGTH", facts.path("previousFormatError").asText());
            assertFalse(prompt.getContents().contains("incomplete"));
            assertTrue(facts.path("productKnowledge").size() > 0);
            assertFalse(facts.path("completedResults").has("retrieve_product_knowledge"));
            return completed(SkillEngineTests.answer(SkillEngineTests.FINAL));
        }));
        var run = streaming ? runtime.executeStreaming("shopping-recommendation", SkillProgressTests.input(), events::add, delta -> {}, () -> false)
                : runtime.execute("shopping-recommendation", SkillProgressTests.input(), events::add, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(7, calls.get());
        assertEquals(5, run.traces().size());
        assertEquals(1, events.stream().filter(event -> "MODEL_RETRYING".equals(event.type())).count());
        assertFalse(events.stream().anyMatch(event -> "RUN_FAILED".equals(event.type())));
    }

    @Test
    void partialToolBatchIsDiscardedAndAgentChoosesNextToolAfterRecovery() {
        var calls = new AtomicInteger();
        var run = engine(model(prompt -> {
            int round = calls.incrementAndGet();
            if (round <= 3) return completed(SkillEngineTests.scriptedResponse(prompt));
            if (round == 4) return withFinish(SkillEngineTests.tool("discarded", "search_products", "{\"interests\":["), "length");
            if (round == 5) {
                assertEquals("AGENT_RECOVERY", StandardSkillTests.facts(prompt).path("phase").asText());
                assertEquals(2, StandardSkillTests.facts(prompt).path("completedResults").size());
                assertFalse(prompt.getContents().contains("discarded"));
                return completed(SkillEngineTests.tool("replanned", "search_products", "{\"interests\":[\"跑步\"],\"budget\":800,\"limit\":8}"));
            }
            if (round == 6) return completed(SkillEngineTests.tool("knowledge", "retrieve_product_knowledge", "{\"productIds\":[\"SKU-001\"],\"question\":\"适用场景\"}"));
            return completed(SkillEngineTests.answer(SkillEngineTests.FINAL));
        })).executeStreaming("shopping-recommendation", SkillProgressTests.input(), event -> {}, delta -> {}, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(7, calls.get());
        assertEquals(5, run.traces().size());
        assertFalse(run.traces().stream().anyMatch(trace -> "discarded".equals(trace.id())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void repetitiveStreamCancelsUpstreamAndRecoversWithoutExposingReasoning(boolean reasoning) {
        var calls = new AtomicInteger();
        var chunks = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        var deltas = new ArrayList<SkillEngine.ContentDelta>();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Must stream"); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                int round = calls.incrementAndGet();
                if (round < 6) return Flux.just(completed(SkillEngineTests.scriptedResponse(prompt)));
                if (round > 6) return Flux.just(completed(SkillEngineTests.answer(SkillEngineTests.FINAL)));
                String repeated = "反复检查同一项商品的既有资料，重新分析相同的已知事实，不断重复相同结论。";
                return Flux.range(0, 10000).map(index -> {
                    chunks.incrementAndGet();
                    var message = reasoning ? AssistantMessage.builder().content("").properties(Map.of("reasoning_content", repeated)).build()
                            : AssistantMessage.builder().content(repeated).build();
                    return new ChatResponse(List.of(new Generation(message)));
                }).doOnCancel(() -> cancelled.set(true));
            }
        };
        var run = engine(model).executeStreaming("shopping-recommendation", SkillProgressTests.input(), event -> {}, deltas::add, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertTrue(cancelled.get());
        assertTrue(chunks.get() < 100);
        assertEquals(5, run.traces().size());
        assertEquals(7, calls.get());
        if (reasoning) assertTrue(deltas.stream().noneMatch(delta -> delta.text().contains("反复检查")));
    }

    @Test
    void persistentGenerationFailureAndContentFilterRemainBounded() {
        for (String finish : List.of("length", "content_filter")) {
            var calls = new AtomicInteger();
            var events = new ArrayList<SkillEngine.Progress>();
            var run = engine(model(prompt -> calls.incrementAndGet() < 6 ? completed(SkillEngineTests.scriptedResponse(prompt))
                    : withFinish(SkillEngineTests.answer("invalid"), finish)))
                    .executeStreaming("shopping-recommendation", SkillProgressTests.input(), events::add, delta -> {}, () -> false);
            assertEquals("FAILED", run.status());
            assertEquals("length".equals(finish) ? 8 : 6, calls.get());
            assertEquals("length".equals(finish) ? 2 : 0, events.stream().filter(event -> "MODEL_RETRYING".equals(event.type())).count());
            assertEquals(5, run.traces().size());
            assertTrue(run.output().isEmpty());
        }
    }

    @Test
    void cancellationAfterRecoveryPreventsFurtherCalls() {
        var calls = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        var runtime = engine(model(prompt -> calls.incrementAndGet() < 6 ? completed(SkillEngineTests.scriptedResponse(prompt))
                : withFinish(SkillEngineTests.answer("invalid"), "length")));
        assertThrows(java.util.concurrent.CancellationException.class, () -> runtime.executeStreaming("shopping-recommendation", SkillProgressTests.input(),
                event -> { if ("MODEL_RETRYING".equals(event.type())) cancelled.set(true); }, delta -> {}, cancelled::get));
        assertEquals(6, calls.get());
    }

    @Test
    void eachAgentRoundGetsOneCurrentCheckpointWithoutFixedToolSequence() {
        var calls = new AtomicInteger();
        var run = engine(model(prompt -> {
            int round = calls.incrementAndGet();
            if (round > 1) {
                var checkpoints = prompt.getInstructions().stream().filter(SystemMessage.class::isInstance)
                        .filter(message -> message.getText().startsWith("[RUNTIME_EXECUTION_STATE]")).toList();
                assertEquals(1, checkpoints.size());
                assertTrue(checkpoints.get(0).getText().contains("remainingModelRoundsIncludingCurrent"));
            }
            return completed(SkillEngineTests.scriptedResponse(prompt));
        })).execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(6, calls.get());
        assertTrue(run.traces().stream().allMatch(trace -> trace.modelRound() > 0));
    }
}
