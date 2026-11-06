package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.bootstrap.RagConfiguration;
import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagKnowledgeBase;
import com.jd.diviner.ai.rec.odin.demo.app.bootstrap.RagStartupLifecycle;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import static org.junit.jupiter.api.Assertions.*;

class RagStartupTests {
    static RagKnowledgeBase knowledgeBase(AtomicInteger calls) {
        return knowledgeBase(calls, () -> {});
    }

    static RagKnowledgeBase knowledgeBase(AtomicInteger calls, Runnable beforeEmbedding) {
        var embedding = new AbstractEmbeddingModel() {
            public float[] embed(Document document) { return embed(document.getText()); }
            public EmbeddingResponse call(EmbeddingRequest request) {
                beforeEmbedding.run();
                calls.incrementAndGet();
                return new EmbeddingResponse(java.util.stream.IntStream.range(0, request.getInstructions().size())
                        .mapToObj(index -> new Embedding(new float[] {1, 0, 0}, index)).toList());
            }
        };
        Resource resource = new ByteArrayResource(("# 商品说明\n\n这款背包可装十四英寸电脑，仅支持防泼水，不可浸泡；雨天请使用配套防雨罩。")
                .getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() { return "backpack.md"; }
        };
        return new RagKnowledgeBase(embedding, "test-embedding", () -> new Resource[] {resource}, 2, 0.5);
    }

    private SpringApplication application(Map<String, RagKnowledgeBase> knowledgeBases, AtomicInteger readyEvents) {
        var application = new SpringApplication(StartupConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        application.setLogStartupInfo(false);
        application.setDefaultProperties(Map.of("spring.config.location", "optional:classpath:/rag-startup-test.properties",
                "spring.main.banner-mode", "off", "spring.application.name", "rag-startup-test"));
        application.addInitializers(context -> knowledgeBases.forEach((name, knowledgeBase) -> context.getBeanFactory().registerSingleton(name, knowledgeBase)));
        application.addListeners(event -> {
            if (event instanceof ApplicationReadyEvent ready) {
                assertTrue(knowledgeBases.values().stream().allMatch(knowledgeBase -> knowledgeBase.status().ready()));
                assertEquals(1, ready.getApplicationContext().getBean("laterRunnerCalls", AtomicInteger.class).get());
                readyEvents.incrementAndGet();
            }
        });
        return application;
    }

    @Test
    void httpOnlyStartsAfterAllIndexesAreReadyIncludingLazyBoot() throws Exception {
        var indexingStarted = new java.util.concurrent.CountDownLatch(1);
        var allowIndexing = new java.util.concurrent.CountDownLatch(1);
        var calls = new AtomicInteger();
        var product = knowledgeBase(calls, () -> {
            indexingStarted.countDown();
            try {
                if (!allowIndexing.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Index test timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Index test interrupted");
            }
        });
        var general = knowledgeBase(calls);
        var readyEvents = new AtomicInteger();
        var port = new AtomicInteger();
        int configuredPort;
        try (var socket = new java.net.ServerSocket(0)) { configuredPort = socket.getLocalPort(); }
        var application = application(Map.of("productRagKnowledgeBase", product, "ragKnowledgeBase", general), readyEvents);
        application.setLazyInitialization(true);
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("isolated-rag-test-port", Map.of("test.rag.port", configuredPort))));
        application.addPrimarySources(List.of(WebConfiguration.class));
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.addListeners(event -> {
            if (event instanceof org.springframework.boot.web.context.WebServerInitializedEvent started) {
                assertTrue(product.status().ready());
                assertTrue(general.status().ready());
                port.set(started.getWebServer().getPort());
            }
        });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> application.run(
                "--server.port=" + configuredPort, "--server.address=127.0.0.1", "--spring.main.lazy-initialization=true"), executor);
        try {
            assertTrue(indexingStarted.await(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, port.get(), "HTTP must not start before RAG is ready");
            assertEquals(0, readyEvents.get());
            assertFalse(product.status().ready());
            assertFalse(future.isDone());
            assertThrows(java.io.IOException.class, () -> {
                try (var socket = new java.net.Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", configuredPort), 1000);
                }
            });
            allowIndexing.countDown();
            future.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(configuredPort, port.get());
            var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
            for (String path : List.of("skills.html", "skills.js", "skills.css")) {
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port.get() + "/" + path))
                        .timeout(java.time.Duration.ofSeconds(5)).GET().build();
                var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), path);
                assertFalse(response.body().isBlank(), path);
            }
            assertEquals(1, readyEvents.get());
            assertTrue(product.status().ready());
            assertTrue(general.status().ready());
        } finally {
            allowIndexing.countDown();
            try {
                future.get(15, java.util.concurrent.TimeUnit.SECONDS).close();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.ImportAutoConfiguration({
            org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration.class,
            org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration.class,
            org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class,
            org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration.class})
    static class WebConfiguration {
        @Bean
        org.springframework.boot.web.servlet.server.ServletWebServerFactory testWebServerFactory(org.springframework.core.env.Environment environment) {
            return new org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory() {
                @Override
                public org.springframework.boot.web.server.WebServer getWebServer(
                        org.springframework.boot.web.servlet.ServletContextInitializer... initializers) {
                    setPort(environment.getProperty("test.rag.port", Integer.class, 0));
                    setAddress(java.net.InetAddress.getLoopbackAddress());
                    return super.getWebServer(initializers);
                }
            };
        }
    }

    @Test
    void bootPreloadsBothIndexesBeforeLaterRunnersAndReadyEvent() {
        var calls = new AtomicInteger();
        var product = knowledgeBase(calls);
        var general = knowledgeBase(calls);
        var readyEvents = new AtomicInteger();
        assertFalse(product.status().ready());
        assertFalse(general.status().ready());
        try (var context = application(Map.of("productRagKnowledgeBase", product, "ragKnowledgeBase", general), readyEvents).run()) {
            assertEquals(1, readyEvents.get());
            assertTrue(calls.get() > 0);
            int startupCalls = calls.get();
            String productVersion = product.status().version();
            product.ensureReady();
            general.ensureReady();
            assertEquals(startupCalls, calls.get());
            assertEquals(productVersion, product.status().version());
            assertFalse(product.retrieve("背包是否防水").sources().isEmpty());
            assertEquals(startupCalls + 1, calls.get());
        }
    }

    @Test
    void startupFailurePreventsReadyEventAndDoesNotLeakUpstreamError() {
        var failing = org.mockito.Mockito.mock(RagKnowledgeBase.class);
        org.mockito.Mockito.when(failing.status()).thenReturn(new RagKnowledgeBase.Status(false, false, "test-embedding", "memory",
                2, 0.5, List.of(), 0, null, null));
        org.mockito.Mockito.doThrow(new IllegalStateException("Bearer private-startup-secret")).when(failing).ensureReady();
        var readyEvents = new AtomicInteger();
        var failure = assertThrows(IllegalStateException.class, () -> application(Map.of("productRagKnowledgeBase", failing), readyEvents).run());
        assertEquals(0, readyEvents.get());
        boolean foundStartupId = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertFalse(String.valueOf(cause.getMessage()).contains("private-startup-secret"));
            foundStartupId |= String.valueOf(cause.getMessage()).contains("startupId=");
            if (String.valueOf(cause.getMessage()).contains("startupId=")) {
                assertTrue(cause.getMessage().contains("productRagKnowledgeBase"));
                assertTrue(cause.getMessage().contains("IllegalStateException"));
                assertNotNull(cause.getCause());
            }
        }
        assertTrue(foundStartupId);
    }

    @Test
    void failedWarmupNeverStartsTheHttpServer() {
        var calls = new AtomicInteger();
        var failing = knowledgeBase(calls, () -> { throw new IllegalStateException("Embedding unavailable"); });
        var readyEvents = new AtomicInteger();
        var httpEvents = new AtomicInteger();
        var application = application(Map.of("productRagKnowledgeBase", failing), readyEvents);
        application.addPrimarySources(List.of(WebConfiguration.class));
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.addListeners(event -> {
            if (event instanceof org.springframework.boot.web.context.WebServerInitializedEvent) httpEvents.incrementAndGet();
        });
        assertThrows(IllegalStateException.class, application::run);
        assertEquals(0, httpEvents.get());
        assertEquals(0, readyEvents.get());
        assertFalse(failing.status().ready());
        assertFalse(failing.status().indexing());
    }

    @Test
    void singletonInitializationPreloadsBeforeRefreshAndDoesNotRebuild() {
        var calls = new AtomicInteger();
        var knowledgeBase = knowledgeBase(calls);
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean("productRagKnowledgeBase", RagKnowledgeBase.class, () -> knowledgeBase);
            context.registerBean("ragStartupLifecycle", RagStartupLifecycle.class,
                    () -> new RagStartupLifecycle(Map.of("productRagKnowledgeBase", knowledgeBase), context));
            context.refresh();
            assertTrue(knowledgeBase.status().ready());
            assertTrue(calls.get() > 0);
            int beforeRunner = calls.get();
            context.getBean(RagStartupLifecycle.class).afterSingletonsInstantiated();
            assertEquals(beforeRunner, calls.get());
        }
    }

    @Test
    void lazyBootPreloadsBeforeContextRefreshAndReadyEvent() {
        var calls = new AtomicInteger();
        var product = knowledgeBase(calls);
        var general = knowledgeBase(calls);
        var application = application(Map.of("productRagKnowledgeBase", product, "ragKnowledgeBase", general), new AtomicInteger());
        application.setLazyInitialization(true);
        application.addListeners(event -> {
            if (event instanceof org.springframework.context.event.ContextRefreshedEvent) {
                assertTrue(product.status().ready());
                assertTrue(general.status().ready());
            }
        });
        try (var context = application.run("--spring.main.lazy-initialization=true")) {
            assertTrue(product.status().ready());
            assertTrue(general.status().ready());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StartupConfiguration {
        @Bean
        @org.springframework.context.annotation.Lazy(false)
        RagStartupLifecycle ragStartupLifecycle(Map<String, RagKnowledgeBase> knowledgeBases, ConfigurableApplicationContext context) {
            return new RagConfiguration().ragStartupLifecycle(knowledgeBases, context);
        }

        @Bean
        AtomicInteger laterRunnerCalls() { return new AtomicInteger(); }

        @Bean
        ApplicationRunner laterRunner(Map<String, RagKnowledgeBase> knowledgeBases, AtomicInteger laterRunnerCalls) {
            return arguments -> {
                assertTrue(knowledgeBases.values().stream().allMatch(knowledgeBase -> knowledgeBase.status().ready()));
                laterRunnerCalls.incrementAndGet();
            };
        }
    }
}
