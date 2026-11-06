package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillRetryPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;

class SkillRetryTests {
    private SkillEngine engine(ChatModel model, SkillRetryPolicy retry) {
        var registry = SkillEngineTests.registry();
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, SkillEngineTests.agentCatalogResource(), registry);
        return new SkillEngine(SkillEngineTests.MAPPER, catalog, registry, new SkillModelClient(model, "test-model",
                Duration.ofMillis(150), Duration.ofSeconds(2), Duration.ofMillis(40), retry));
    }

    static ChatResponse complete(Prompt prompt) {
        var response = SkillEngineTests.scriptedResponse(prompt);
        return new ChatResponse(List.of(new Generation(response.getResult().getOutput(),
                ChatGenerationMetadata.builder().finishReason(response.hasToolCalls() ? "tool_calls" : "stop").build())));
    }

    @Test
    void retriesCurrentPromptWithoutReplayingCompletedTools() {
        var calls = new AtomicInteger();
        ChatModel model = prompt -> {
            if (calls.incrementAndGet() == 4) throw new IllegalStateException(new java.io.IOException("temporary"));
            return SkillEngineTests.scriptedResponse(prompt);
        };
        var events = new ArrayList<SkillEngine.Progress>();
        var run = engine(model, new SkillRetryPolicy(3, Duration.ZERO)).execute("shopping-recommendation",
                SkillProgressTests.input(), events::add, () -> false);
        assertEquals("SUCCESS", run.status());
        assertEquals(7, calls.get());
        assertEquals(6, run.modelCalls());
        assertEquals(5, run.traces().size());
        assertEquals(1, events.stream().filter(event -> "MODEL_RETRYING".equals(event.type())).count());
        assertEquals(4, events.stream().filter(event -> "MODEL_RETRYING".equals(event.type())).findFirst().orElseThrow().modelRound());
    }

    @Test
    void replacesFailedDraftAndOnlyExecutesToolsFromCompleteAttempts() {
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError(); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                if (calls.incrementAndGet() == 1) return Flux.concat(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("abandoned draft"))))),
                        Flux.error(new java.io.IOException("connection lost")));
                return Flux.just(complete(prompt));
            }
        };
        var text = new StringBuilder();
        var sequences = new ArrayList<Integer>();
        var run = engine(model, new SkillRetryPolicy(3, Duration.ZERO)).executeStreaming("shopping-recommendation", SkillProgressTests.input(),
                event -> { if ("MODEL_RETRYING".equals(event.type())) text.setLength(0); },
                delta -> { text.append(delta.text()); sequences.add(delta.sequence()); }, () -> false);
        assertEquals("SUCCESS", run.status());
        assertFalse(text.toString().contains("abandoned"));
        assertEquals(List.of(1, 2), sequences);
        assertEquals(5, run.traces().size());
        assertEquals(7, calls.get());
    }

    @Test
    void retriesEofWithoutExecutingUnfinishedToolCall() {
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError(); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(calls.incrementAndGet() == 1 ? SkillEngineTests.scriptedResponse(prompt) : complete(prompt));
            }
        };
        var run = engine(model, new SkillRetryPolicy(3, Duration.ZERO)).executeStreaming("shopping-recommendation", SkillProgressTests.input(),
                event -> {}, delta -> {}, () -> false);
        assertEquals("SUCCESS", run.status());
        assertEquals(7, calls.get());
        assertEquals(5, run.traces().size());
    }

    @Test
    void stopsAtAttemptLimitAndDoesNotRetryAuthenticationErrors() {
        for (int status : List.of(401, 403, 400, 429, 503)) {
            var calls = new AtomicInteger();
            var run = engine(prompt -> {
                calls.incrementAndGet();
                throw WebClientResponseException.create(status, "private details", HttpHeaders.EMPTY, new byte[0], null);
            }, new SkillRetryPolicy(3, Duration.ZERO)).execute("shopping-recommendation", SkillProgressTests.input());
            assertEquals("FAILED", run.status());
            assertEquals(status == 429 || status == 503 ? 3 : 1, calls.get());
            assertTrue(run.traces().isEmpty());
        }
    }

    @Test
    void cancellationDuringRetryPreventsAnotherAttempt() {
        var cancelled = new AtomicBoolean();
        var calls = new AtomicInteger();
        var engine = engine(prompt -> {
            calls.incrementAndGet();
            throw new IllegalStateException(new java.io.IOException("temporary"));
        }, new SkillRetryPolicy(3, Duration.ZERO));
        assertThrows(java.util.concurrent.CancellationException.class, () -> engine.execute("shopping-recommendation", SkillProgressTests.input(),
                event -> { if ("MODEL_RETRYING".equals(event.type())) cancelled.set(true); }, cancelled::get));
        assertEquals(1, calls.get());
    }

    @Test
    void skipsRetryWhenBackoffExceedsRemainingRoundBudget() {
        var calls = new AtomicInteger();
        var run = engine(prompt -> {
            calls.incrementAndGet();
            throw new IllegalStateException(new java.io.IOException("temporary"));
        }, new SkillRetryPolicy(3, Duration.ofSeconds(5))).execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("FAILED", run.status());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsInvalidRetryConfigurationAndDoesNotTreatBadJsonAsNetworkFailure() {
        assertThrows(IllegalArgumentException.class, () -> new SkillRetryPolicy(4, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new SkillRetryPolicy(3, Duration.ofSeconds(6)));
        var retry = new SkillRetryPolicy(3, Duration.ofSeconds(1));
        assertEquals(1000, retry.delayMillis(1));
        assertEquals(2000, retry.delayMillis(2));
        assertFalse(retry.transientFailure(new java.util.concurrent.CancellationException()));
        try { SkillEngineTests.MAPPER.readTree("{"); }
        catch (Exception error) { assertFalse(retry.transientFailure(error)); }
    }
}
