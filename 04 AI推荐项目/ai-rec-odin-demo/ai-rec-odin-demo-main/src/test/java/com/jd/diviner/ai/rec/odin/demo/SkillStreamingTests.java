package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.web.controller.SkillController;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SkillStreamingTests {

    private SkillEngine timedEngine(ChatModel model, Duration idle, Duration total) {
        var registry = SkillEngineTests.registry();
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, SkillEngineTests.agentCatalogResource(), registry);
        return new SkillEngine(SkillEngineTests.MAPPER, catalog, registry,
                new com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient(model, "test", idle, total, Duration.ofMillis(10)));
    }

    @Test
    void silentModelEmitsHonestWaitingThenTimesOutAndCancelsUpstream() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Must stream"); }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)); }
        };
        var events = new CopyOnWriteArrayList<SkillEngine.Progress>();
        var run = timedEngine(model, Duration.ofMillis(150), Duration.ofSeconds(2)).executeStreaming(
                "shopping-recommendation", SkillProgressTests.input(), events::add, delta -> fail("No content received"), () -> false);
        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("超时"));
        assertTrue(run.error().contains(run.runId()));
        assertTrue(cancelled.get());
        assertTrue(events.stream().anyMatch(event -> event.type().equals("MODEL_WAITING") && event.message().contains("尚未收到模型响应")));
        assertTrue(run.traces().isEmpty());
        for (int index = 0; index < events.size(); index++) assertEquals(index + 1, events.get(index).sequence());
    }

    @Test
    void continuousNonContentChunksCannotExtendRoundDeadlineOrLeakReasoning() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Must stream"); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.interval(Duration.ofMillis(5)).map(index -> new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("").properties(java.util.Map.of("reasoningContent", "private reasoning secret " + index)).build()))))
                        .doOnCancel(() -> cancelled.set(true));
            }
        };
        var events = new CopyOnWriteArrayList<SkillEngine.Progress>();
        var run = timedEngine(model, Duration.ofSeconds(2), Duration.ofMillis(180)).executeStreaming(
                "shopping-recommendation", SkillProgressTests.input(), events::add, delta -> fail("Reasoning is not content"), () -> false);
        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("超时"));
        assertTrue(cancelled.get());
        assertFalse(events.toString().contains("private reasoning secret"));
        assertTrue(events.stream().anyMatch(event -> event.message().contains("模型正在推理，尚未生成正文")));
    }

    @Test
    void brokenModelConnectionHasDistinctSafeErrorAndDoesNotRetry() {
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Must stream"); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                calls.incrementAndGet();
                return Flux.error(new java.io.IOException("chunked transfer encoding, secret upstream details"));
            }
        };
        var run = timedEngine(model, Duration.ofSeconds(1), Duration.ofSeconds(2)).executeStreaming(
                "shopping-recommendation", SkillProgressTests.input(), event -> {}, delta -> fail(), () -> false);
        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("连接中断"));
        assertFalse(run.error().contains("secret"));
        assertEquals(1, calls.get());
    }

    private ChatResponse chunk(String text, String finishReason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())));
    }

    @Test
    void contentReachesSseWhileModelStillGeneratingAndCancellationDisposesUpstream() throws Exception {
        Sinks.Many<ChatResponse> upstream = Sinks.many().unicast().onBackpressureBuffer();
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        List<ServerSentEvent<Object>> events = new CopyOnWriteArrayList<>();
        SkillController controller = streamingController(new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Must stream"); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                return upstream.asFlux().doOnSubscribe(subscription -> subscribed.countDown()).doOnCancel(cancelled::countDown);
            }
        });
        var subscription = controller.stream("shopping-recommendation", requestBody()).getBody().subscribe(event -> {
            events.add(event);
            if ("content".equals(event.event())) received.countDown();
        });
        try {
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            upstream.tryEmitNext(chunk("正在生成的内容", null));
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertFalse(events.stream().anyMatch(event -> "result".equals(event.event())));
            SkillEngine.ContentDelta delta = (SkillEngine.ContentDelta) events.get(events.size() - 1).data();
            assertEquals("正在生成的内容", delta.text());
            assertEquals(1, delta.sequence());
            subscription.dispose();
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        } finally { subscription.dispose(); }
    }

    @Test
    void incompleteOrTruncatedModelStreamCannotExecuteTools() {
        for (String finishReason : new String[] {null, "length", "content_filter"}) {
            ChatModel model = new ChatModel() {
                public ChatResponse call(Prompt prompt) { throw new AssertionError("Must stream"); }
                public Flux<ChatResponse> stream(Prompt prompt) {
                    var tool = SkillEngineTests.tool("call-1", "Skill", "{\"command\":\"shopping-recommendation\"}");
                    return Flux.just(new ChatResponse(List.of(new Generation(tool.getResult().getOutput(),
                            ChatGenerationMetadata.builder().finishReason(finishReason).build()))));
                }
            };
            var run = SkillProgressTests.engine(model).executeStreaming("shopping-recommendation", SkillProgressTests.input(),
                    event -> {}, delta -> fail("Tool arguments must never become content"), () -> false);
            assertEquals("FAILED", run.status());
            assertTrue(run.traces().isEmpty());
            assertEquals(1, run.modelCalls());
        }
    }

    @Test
    void partialContentFollowedByUpstreamErrorIsNeverReportedAsSuccess() {
        List<SkillEngine.ContentDelta> deltas = new CopyOnWriteArrayList<>();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Must stream"); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.concat(Flux.just(chunk("{\"summary\":", null)), Flux.error(new IllegalStateException("upstream secret")));
            }
        };
        var run = SkillProgressTests.engine(model).executeStreaming("shopping-recommendation", SkillProgressTests.input(),
                event -> {}, deltas::add, () -> false);
        assertEquals("{\"summary\":", deltas.get(0).text());
        assertEquals("FAILED", run.status());
        assertTrue(run.traces().isEmpty());
        assertFalse(run.error().contains("upstream secret"));
    }

    private SkillController controller(ChatModel model) {
        return streamingController(new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Streaming must not use call()"); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> {
                    ChatResponse response = model.call(prompt);
                    return Flux.just(new ChatResponse(List.of(new Generation(response.getResult().getOutput(),
                            ChatGenerationMetadata.builder().finishReason(response.hasToolCalls() ? "tool_calls" : "stop").build())),
                            response.getMetadata()));
                });
            }
        });
    }

    private SkillController streamingController(ChatModel model) {
        var registry = SkillEngineTests.registry();
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, SkillEngineTests.agentCatalogResource(), registry);
        return new SkillController(catalog, SkillProgressTests.engine(model), registry);
    }

    private com.fasterxml.jackson.databind.JsonNode requestBody() {
        return SkillEngineTests.MAPPER.createObjectNode().set("input", SkillProgressTests.input());
    }

    @Test
    void emitsProgressBeforeSlowModelReturns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        List<ServerSentEvent<Object>> events = new CopyOnWriteArrayList<>();
        SkillController controller = controller(prompt -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            return SkillEngineTests.scriptedResponse(prompt);
        });
        var subscription = controller.stream("shopping-recommendation", requestBody()).getBody()
                .subscribe(events::add, error -> finished.countDown(), finished::countDown);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("RUN_STARTED", "MODEL_STARTED"), events.stream()
                    .map(event -> ((SkillEngine.Progress) event.data()).type()).toList());
            assertFalse(events.stream().anyMatch(event -> "result".equals(event.event())));
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals("result", events.get(events.size() - 1).event());
        } finally { release.countDown(); subscription.dispose(); }
    }

    @Test
    void emitsFailedRunWithTraceInsteadOfSuccessResult() {
        List<ServerSentEvent<Object>> events = controller(prompt -> { throw new IllegalStateException("secret"); })
                .stream("shopping-recommendation", requestBody()).getBody().collectList().block(Duration.ofSeconds(5));
        var last = SkillEngineTests.MAPPER.valueToTree(events.get(events.size() - 1).data());
        assertEquals("result", events.get(events.size() - 1).event());
        assertEquals(500, last.path("code").asInt());
        assertEquals("FAILED", last.at("/data/status").asText());
        assertTrue(last.at("/data/modelTurns").isArray());
        assertFalse(last.toString().contains("secret"));
    }

    @Test
    void cancellationStopsBeforeFurtherModelRequests() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<ServerSentEvent<Object>> events = new CopyOnWriteArrayList<>();
        SkillController controller = controller(prompt -> {
            calls.incrementAndGet();
            entered.countDown();
            try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exited.countDown(); }
            return SkillEngineTests.scriptedResponse(prompt);
        });
        var subscription = controller.stream("shopping-recommendation", requestBody()).getBody().subscribe(events::add);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            subscription.dispose();
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(2, events.size());
        } finally { subscription.dispose(); }
    }

    @Test
    void servesSseOverHttpWithNoBufferingHeadersAndCompleteResult() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(SkillEngineTests::scriptedResponse))
                .addInterceptors(new com.jd.diviner.ai.rec.odin.demo.web.controller.SkillStreamLifecycleInterceptor()).build();
        var result = mvc.perform(post("/api/skills/shopping-recommendation/stream")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content(requestBody().toString()))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        String body = mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.startsWith("event:progress"));
        assertTrue(body.contains("\"type\":\"TOOL_COMPLETED\""));
        assertTrue(body.contains("event:result"));
        assertTrue(body.contains("event:content"));
        assertTrue(body.indexOf("event:content") < body.indexOf("VALIDATION_STARTED"));
        assertTrue(body.contains("\"code\":0"));
        assertTrue(body.indexOf("RUN_STARTED") < body.indexOf("MODEL_STARTED"));
        assertTrue(body.indexOf("RUN_COMPLETED") < body.indexOf("event:result"));
    }

    @Test
    void validatesBeforeStartingSseAndReturnsNormalHttpErrors() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(prompt -> { fail("Model must not be called"); return null; })).build();
        for (String body : List.of("{}", "{", "{\"input\":{\"username\":\"bad\\npin\"}}")) {
            mvc.perform(post("/api/skills/shopping-recommendation/stream").contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM).content(body))
                    .andExpect(status().isBadRequest()).andExpect(request().asyncNotStarted())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }
        mvc.perform(post("/api/skills/missing/stream").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content(requestBody().toString()))
                .andExpect(status().isNotFound()).andExpect(request().asyncNotStarted());
        mvc.perform(post("/api/skills/beijing-weather-travel/stream").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content(requestBody().toString()))
                .andExpect(status().isBadRequest()).andExpect(request().asyncNotStarted());
    }
}
