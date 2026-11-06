package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import com.jd.diviner.ai.rec.odin.demo.web.controller.SkillStreamSessions;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.*;

class SkillSessionTests {
    @Test
    void sessionDeadlineUsesEngineBudgetInsteadOfHardcodedFiveMinutes() {
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError(); }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.never(); }
        };
        assertEquals(Duration.ofSeconds(1290), SkillProgressTests.engine(model).clientTimeout());
        var registry = SkillEngineTests.registry();
        var catalog = new com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog(SkillEngineTests.MAPPER,
                SkillEngineTests.agentCatalogResource(), registry);
        var engine = new com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine(SkillEngineTests.MAPPER, catalog, registry,
                new com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient(model, "test-model")) {
            @Override public Duration clientTimeout() { return Duration.ofMillis(200); }
        };
        var sessions = new SkillStreamSessions(engine, SkillEngineTests.MAPPER);
        try {
            var event = sessions.connect(UUID.randomUUID().toString(), "shopping-recommendation",
                    SkillProgressTests.input(), false, ExecutionLog.current()).blockLast(Duration.ofSeconds(3));
            assertNotNull(event);
            assertEquals("error", event.event());
            assertTrue(event.data().toString().contains("总存活时限"));
        } finally { sessions.destroy(); }
    }

    @Test
    void reconnectsAndReplaysOneExecutionInsteadOfRepeatingTools() throws Exception {
        var calls = new AtomicInteger();
        Sinks.One<Void> gate = Sinks.one();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError(); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                calls.incrementAndGet();
                return gate.asMono().thenMany(Flux.just(SkillRetryTests.complete(prompt)));
            }
        };
        var sessions = new SkillStreamSessions(SkillProgressTests.engine(model), SkillEngineTests.MAPPER);
        String id = UUID.randomUUID().toString();
        try {
            var prefix = sessions.connect(id, "shopping-recommendation", SkillProgressTests.input(), false, ExecutionLog.current())
                    .take(2).collectList().block(Duration.ofSeconds(3));
            assertEquals(2, prefix.size());
            var resumed = sessions.connect(id, "shopping-recommendation", SkillProgressTests.input(), true, ExecutionLog.current())
                    .collectList().toFuture();
            var concurrent = sessions.connect(id, "shopping-recommendation", SkillProgressTests.input(), true, ExecutionLog.current())
                    .collectList().toFuture();
            gate.tryEmitEmpty();
            var events = resumed.get(5, TimeUnit.SECONDS);
            assertEquals(events, concurrent.get(5, TimeUnit.SECONDS));
            assertEquals("result", events.get(events.size() - 1).event());
            assertEquals(6, calls.get());
            assertEquals(prefix, events.subList(0, 2));
            var replay = sessions.connect(id, "shopping-recommendation", SkillProgressTests.input(), true, ExecutionLog.current())
                    .collectList().block(Duration.ofSeconds(3));
            assertEquals(events, replay);
            assertEquals(6, calls.get());
        } finally { sessions.destroy(); }
    }

    @Test
    void sessionEndpointReplaysCompletedHttpResponseWithoutRunningAgain() throws Exception {
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError(); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                calls.incrementAndGet();
                return Flux.just(SkillRetryTests.complete(prompt));
            }
        };
        var engine = SkillProgressTests.engine(model);
        var sessions = new SkillStreamSessions(engine, SkillEngineTests.MAPPER);
        var registry = SkillEngineTests.registry();
        var catalog = new com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog(SkillEngineTests.MAPPER,
                SkillEngineTests.agentCatalogResource(), registry);
        var controller = new com.jd.diviner.ai.rec.odin.demo.web.controller.SkillController(catalog, engine, registry, sessions);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        String id = UUID.randomUUID().toString();
        String first = null;
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/skills/shopping-recommendation/session-stream").param("sessionId", id).param("resume", "true")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"input\":{\"username\":\"xiaoming\"}}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isGone())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(410));
            assertEquals(0, calls.get());
            for (boolean resume : new boolean[] {false, true}) {
                var pending = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/skills/shopping-recommendation/session-stream").param("sessionId", id).param("resume", Boolean.toString(resume))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"input\":{\"username\":\"xiaoming\"}}"))
                        .andReturn();
                pending.getAsyncResult(5000);
                var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(pending)).andReturn().getResponse();
                assertEquals(200, response.getStatus());
                assertEquals(id, response.getHeader("X-Skill-Session"));
                String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(body.contains("event:result"));
                if (first == null) first = body;
                else assertEquals(first, body);
            }
            assertEquals(6, calls.get());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete("/api/skills/shopping-recommendation/sessions/" + id))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        } finally { sessions.destroy(); }
    }

    @Test
    void expiresDisconnectedSessionAndNeverRestartsMissingOrChangedSessions() throws Exception {
        var calls = new AtomicInteger();
        var cancelled = new CountDownLatch(1);
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError(); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                calls.incrementAndGet();
                return Flux.<ChatResponse>never().doOnCancel(cancelled::countDown);
            }
        };
        var sessions = new SkillStreamSessions(SkillProgressTests.engine(model), SkillEngineTests.MAPPER,
                Duration.ofMillis(150), Duration.ofSeconds(5), 1, 100, 100000);
        String id = UUID.randomUUID().toString();
        try {
            assertEquals(410, assertThrows(ResponseStatusException.class, () -> sessions.connect(id, "shopping-recommendation",
                    SkillProgressTests.input(), true, ExecutionLog.current())).getStatusCode().value());
            sessions.connect(id, "shopping-recommendation", SkillProgressTests.input(), false, ExecutionLog.current())
                    .take(2).blockLast(Duration.ofSeconds(3));
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            var replay = sessions.connect(id, "shopping-recommendation", SkillProgressTests.input(), true, ExecutionLog.current())
                    .collectList().block(Duration.ofSeconds(3));
            assertEquals("error", replay.get(replay.size() - 1).event());
            assertEquals(1, calls.get());
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> sessions.connect(id, "other-skill",
                    SkillProgressTests.input(), false, ExecutionLog.current())).getStatusCode().value());
            assertEquals(429, assertThrows(ResponseStatusException.class, () -> sessions.connect(UUID.randomUUID().toString(), "shopping-recommendation",
                    SkillProgressTests.input(), false, ExecutionLog.current())).getStatusCode().value());
        } finally { sessions.destroy(); }
    }

    @Test
    void boundsReplayBufferAndAllowsExplicitCancellation() {
        var calls = new AtomicInteger();
        var sessions = new SkillStreamSessions(SkillProgressTests.engine(prompt -> {
            calls.incrementAndGet();
            return SkillEngineTests.scriptedResponse(prompt);
        }), SkillEngineTests.MAPPER, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, 1, 10000);
        try {
            String id = UUID.randomUUID().toString();
            var events = sessions.connect(id, "shopping-recommendation", SkillProgressTests.input(), false, ExecutionLog.current())
                    .collectList().block(Duration.ofSeconds(3));
            assertEquals(2, events.size());
            assertEquals("error", events.get(1).event());
            assertEquals(0, calls.get());
            String unused = UUID.randomUUID().toString();
            var pending = sessions.connect(unused, "shopping-recommendation", SkillProgressTests.input(), false, ExecutionLog.current());
            sessions.cancel(unused, "shopping-recommendation");
            assertEquals("error", pending.blockLast(Duration.ofSeconds(3)).event());
            assertEquals(0, calls.get());
        } finally { sessions.destroy(); }
    }
}
