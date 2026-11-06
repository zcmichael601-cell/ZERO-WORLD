package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.bootstrap.SkillModelConfiguration;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillRetryPolicy;
import com.jd.diviner.ai.rec.odin.demo.web.controller.SkillStreamingConfiguration;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;

class SkillTimeoutTests {
    private SkillEngine engine(SkillModelClient client) {
        var registry = SkillEngineTests.registry();
        return new SkillEngine(SkillEngineTests.MAPPER,
                new SkillCatalog(SkillEngineTests.MAPPER, SkillEngineTests.agentCatalogResource(), registry), registry, client);
    }

    @Test
    void loadsLargerDefaultsAndSupportsOverridesBeyondOldLimits() throws Exception {
        var environment = new MockEnvironment()
                .withProperty("dong.spring.ai.joyai.chat.base-url", "http://127.0.0.1:1")
                .withProperty("dong.spring.ai.joyai.chat.completions-path", "/v1/chat/completions")
                .withProperty("dong.spring.ai.joyai.chat.api-key", "fixture-key")
                .withProperty("dong.spring.ai.joyai.chat.options.model", "test");
        var defaults = new SkillModelConfiguration().skillModelClient(environment);
        assertEquals(Duration.ofSeconds(180), defaults.idleTimeout());
        assertEquals(Duration.ofSeconds(600), defaults.roundTimeout());
        assertEquals(Duration.ofSeconds(1200), defaults.runTimeout());
        var properties = new java.util.Properties();
        try (var input = new ClassPathResource("skills/runtime.properties").getInputStream()) { properties.load(input); }
        assertEquals("1200", properties.getProperty("odin.skills.run-timeout-seconds"));
        environment.withProperty("odin.skills.model.idle-timeout-seconds", "300")
                .withProperty("odin.skills.model.round-timeout-seconds", "900")
                .withProperty("odin.skills.run-timeout-seconds", "2400");
        var custom = new SkillModelConfiguration().skillModelClient(environment);
        assertEquals(Duration.ofSeconds(300), custom.idleTimeout());
        assertEquals(Duration.ofSeconds(900), custom.roundTimeout());
        assertEquals(Duration.ofSeconds(2400), engine(custom).runTimeout());
        assertEquals(Duration.ofSeconds(2490), engine(custom).clientTimeout());
        for (String invalid : new String[]{"0", "-1", "3601"}) {
            environment.withProperty("odin.skills.run-timeout-seconds", invalid);
            assertThrows(IllegalArgumentException.class, () -> new SkillModelConfiguration().skillModelClient(environment));
        }
    }

    @Test
    void nonStreamingHttpWaitsForRoundDeadlineRatherThanStreamIdleDeadline() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(1500); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            byte[] body = """
                    {"id":"fixture","object":"chat.completion","created":1,"model":"test",
                    "choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var environment = new MockEnvironment()
                    .withProperty("dong.spring.ai.joyai.chat.base-url", "http://127.0.0.1:" + server.getAddress().getPort())
                    .withProperty("dong.spring.ai.joyai.chat.completions-path", "/v1/chat/completions")
                    .withProperty("dong.spring.ai.joyai.chat.api-key", "fixture-key")
                    .withProperty("dong.spring.ai.joyai.chat.options.model", "test")
                    .withProperty("odin.skills.model.idle-timeout-seconds", "1")
                    .withProperty("odin.skills.model.round-timeout-seconds", "5")
                    .withProperty("odin.skills.run-timeout-seconds", "10");
            var client = new SkillModelConfiguration().skillModelClient(environment);
            var response = client.chatModel().call(new Prompt("test"));
            assertEquals("done", response.getResult().getOutput().getText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mvcDeadlineTracksRunBudgetWithRoomToSendFinalResult() {
        class CapturedTimeout extends AsyncSupportConfigurer {
            Long timeout() { return getTimeout(); }
        }
        var configuration = new SkillStreamingConfiguration();
        var captured = new CapturedTimeout();
        configuration.configureAsyncSupport(captured);
        assertEquals(1260000L, captured.timeout());
        ReflectionTestUtils.setField(configuration, "runTimeoutSeconds", 2400L);
        configuration.configureAsyncSupport(captured);
        assertEquals(2460000L, captured.timeout());
    }

    @Test
    void totalRunBudgetStillStopsContinuousStreamBeforeLongerRoundDeadline() {
        var cancelled = new AtomicBoolean();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Must stream"); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.interval(Duration.ofMillis(10)).map(tick -> SkillEngineTests.answer(""))
                        .doOnCancel(() -> cancelled.set(true));
            }
        };
        var client = new SkillModelClient(model, "test", Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofMillis(10), SkillRetryPolicy.disabled(), Duration.ofMillis(150));
        var run = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> engine(client).executeStreaming(
                "shopping-recommendation", SkillProgressTests.input(), progress -> {}, delta -> {}, () -> false));
        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("超时"));
        assertTrue(cancelled.get());
    }

    @Test
    void nonStreamingModelCannotOutliveRemainingRunBudget() throws Exception {
        var interrupted = new CountDownLatch(1);
        ChatModel model = prompt -> {
            try { Thread.sleep(5000); }
            catch (InterruptedException exception) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return SkillEngineTests.answer("{}");
        };
        var client = new SkillModelClient(model, "test", Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofMillis(10), SkillRetryPolicy.disabled(), Duration.ofMillis(150));
        var run = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> engine(client).execute(
                "shopping-recommendation", SkillProgressTests.input()));
        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("超时"));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }
}
