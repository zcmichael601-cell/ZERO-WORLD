package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagKnowledgeBase;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.web.controller.SkillController;
import com.jd.diviner.ai.rec.odin.demo.web.controller.SkillStreamLifecycleInterceptor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;

class SkillDiagnosticsTests {
    private final List<JsonNode> events = new CopyOnWriteArrayList<>();
    private Logger logger;
    private Level previousLevel;
    private AbstractAppender appender;

    @BeforeEach
    void captureDiagnostics() {
        logger = ((LoggerContext) LogManager.getContext(false)).getLogger(ExecutionLog.class.getName());
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new AbstractAppender("skill-diagnostics-test", null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                String message = event.getMessage().getFormattedMessage();
                if (!message.startsWith("agent_diag ")) return;
                try {
                    events.add(SkillEngineTests.MAPPER.readTree(message.substring("agent_diag ".length())));
                } catch (Exception exception) {
                    throw new AssertionError("诊断日志必须为单行 JSON", exception);
                }
            }
        };
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseDiagnostics() throws InterruptedException {
        try {
            ExecutionLog.current().info("TEST_CAPTURE_DRAINED");
            awaitEvent("TEST_CAPTURE_DRAINED");
        } finally {
            logger.removeAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void startupReportsNativeLog4jBackendAndConfiguredInfoLevel() throws Exception {
        var properties = new java.util.Properties();
        try (var stream = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(stream);
        }
        assertEquals("INFO", properties.getProperty("logging.level." + ExecutionLog.class.getName()));
        var configuration = new com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLogConfiguration();
        configuration.agentLoggingStartupReporter().afterPropertiesSet();
        awaitEvent("AGENT_LOGGING_READY");
        assertEquals("LOG4J2", event("AGENT_LOGGING_READY").path("loggingFacade").asText());
        assertEquals(ExecutionLog.class.getName(), event("AGENT_LOGGING_READY").path("logger").asText());
        assertTrue(event("AGENT_LOGGING_READY").path("infoEnabled").asBoolean());
        assertFalse(event("AGENT_LOGGING_READY").path("loggerFactory").asText().isBlank());
    }

    @Test
    void disabledInfoLevelEmitsExplicitWarningWithoutBusinessPayloads() throws Exception {
        logger.setLevel(Level.WARN);
        try {
            ExecutionLog.reportConfiguration();
            ExecutionLog.current().info("TEST_INFO_SHOULD_BE_FILTERED");
            awaitEvent("AGENT_LOGGING_DISABLED");
            assertFalse(event("AGENT_LOGGING_DISABLED").path("infoEnabled").asBoolean());
            assertFalse(events.stream().anyMatch(event -> "TEST_INFO_SHOULD_BE_FILTERED".equals(event.path("event").asText())));
        } finally {
            logger.setLevel(Level.INFO);
        }
    }

    @Test
    void startupLogsReadyOnlyAfterBothIndexesAndApplicationAreReady() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var knowledgeBases = java.util.Map.of("productRagKnowledgeBase", RagStartupTests.knowledgeBase(calls),
                "ragKnowledgeBase", RagStartupTests.knowledgeBase(calls));
        try (var context = new org.springframework.context.support.GenericApplicationContext();
             var otherContext = new org.springframework.context.support.GenericApplicationContext()) {
            context.refresh();
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "http-started-test", java.util.Map.of("local.server.port", "18080")));
            var lifecycle = new com.jd.diviner.ai.rec.odin.demo.app.bootstrap.RagStartupLifecycle(knowledgeBases, context);
            lifecycle.afterSingletonsInstantiated();
            awaitEvent("RAG_STARTUP_COMPLETED");
            assertFalse(events.stream().anyMatch(event -> "HTTP_SERVER_STARTED".equals(event.path("event").asText())));
            assertEquals("BEFORE_WEB_SERVER_START", event("SERVICE_STARTUP_LOADING").path("startupPhase").asText());
            assertTrue(event("RAG_DOCUMENT_LOAD_COMPLETED").path("documents").asInt() > 0);
            assertTrue(event("RAG_VECTOR_BATCH_STARTED").has("batchStart"));
            assertEquals(2, events.stream().filter(event -> "RAG_STARTUP_READY".equals(event.path("event").asText())).count());
            assertFalse(events.stream().anyMatch(event -> "SERVICE_READY".equals(event.path("event").asText())));
            lifecycle.onApplicationEvent(new org.springframework.boot.context.event.ApplicationReadyEvent(
                    new org.springframework.boot.SpringApplication(), new String[0], otherContext, Duration.ofSeconds(1)));
            var ready = new org.springframework.boot.context.event.ApplicationReadyEvent(
                    new org.springframework.boot.SpringApplication(), new String[0], context, Duration.ofSeconds(2));
            lifecycle.onApplicationEvent(ready);
            lifecycle.onApplicationEvent(ready);
            awaitEvent("SERVICE_READY");
            assertEquals("READY", event("SERVICE_READY").path("status").asText());
            assertEquals(2000, event("SERVICE_READY").path("startupMs").asLong());
            assertTrue(event("SERVICE_READY").path("message").asText().contains("服务启动成功"));
            assertEquals(1, events.stream().filter(event -> "SERVICE_READY".equals(event.path("event").asText())).count());
            assertEquals(event("RAG_STARTUP_COMPLETED").path("startupId"), event("SERVICE_READY").path("startupId"));
        }
    }

    @Test
    void startupFailureIsExplicitAndNeverReportsSuccess() throws Exception {
        try (var context = new org.springframework.context.support.GenericApplicationContext()) {
            context.refresh();
            var lifecycle = new com.jd.diviner.ai.rec.odin.demo.app.bootstrap.RagStartupLifecycle(java.util.Map.of(), context);
            assertThrows(IllegalStateException.class, lifecycle::afterSingletonsInstantiated);
            awaitEvent("SERVICE_STARTUP_FAILED");
            assertFalse(event("SERVICE_STARTUP_FAILED").path("startupId").asText().isBlank());
            assertThrows(IllegalStateException.class, () -> lifecycle.onApplicationEvent(new org.springframework.boot.context.event.ApplicationReadyEvent(
                    new org.springframework.boot.SpringApplication(), new String[0], context, Duration.ofSeconds(1))));
            assertFalse(events.stream().anyMatch(event -> "SERVICE_READY".equals(event.path("event").asText())));
        }
    }

    @Test
    void startupExceptionAndConsoleIncludeHttpStatusWithoutUpstreamPayloads() throws Exception {
        var failing = org.mockito.Mockito.mock(RagKnowledgeBase.class);
        org.mockito.Mockito.when(failing.status()).thenReturn(new RagKnowledgeBase.Status(false, false, "test-embedding", "memory",
                2, 0.5, java.util.List.of(), 0, null, null));
        var upstream = new org.springframework.web.client.RestClientResponseException("private-upstream-message", 503, "",
                org.springframework.http.HttpHeaders.EMPTY, "Bearer private-api-key".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        org.mockito.Mockito.doThrow(upstream).when(failing).ensureReady();
        try (var context = new org.springframework.context.support.GenericApplicationContext()) {
            context.refresh();
            var lifecycle = new com.jd.diviner.ai.rec.odin.demo.app.bootstrap.RagStartupLifecycle(java.util.Map.of("productRagKnowledgeBase", failing), context);
            var failure = assertThrows(IllegalStateException.class, lifecycle::afterSingletonsInstantiated);
            assertTrue(failure.getMessage().contains("HTTP 503"));
            assertTrue(failure.getMessage().contains("productRagKnowledgeBase"));
            assertNotNull(failure.getCause());
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                assertFalse(String.valueOf(cause.getMessage()).contains("private-"));
            }
            awaitEvent("SERVICE_STARTUP_FAILED");
            assertTrue(event("SERVICE_STARTUP_FAILED").path("failureSummary").asText().contains("HTTP 503"));
            assertFalse(events.toString().contains("private-"));
            assertFalse(events.stream().anyMatch(event -> "SERVICE_READY".equals(event.path("event").asText())));
        }
    }

    @Test
    void servletTimeoutAndWriteErrorsKeepRequestIdWithoutChangingFrameworkHandling() throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        response.setHeader("X-Request-Id", "servlet-request");
        var interceptor = new SkillStreamLifecycleInterceptor();
        assertTrue(interceptor.preHandle(request, response, new Object()));
        var lifecycle = org.springframework.web.context.request.async.WebAsyncUtils.getAsyncManager(request)
                .getDeferredResultInterceptor(SkillStreamLifecycleInterceptor.class.getName());
        assertNotNull(lifecycle);
        var webRequest = new org.springframework.web.context.request.ServletWebRequest(request, response);
        var result = new org.springframework.web.context.request.async.DeferredResult<Object>();
        lifecycle.beforeConcurrentHandling(webRequest, result);
        assertTrue(lifecycle.handleTimeout(webRequest, result));
        var error = new java.io.IOException("private-disconnect-details");
        assertTrue(lifecycle.handleError(webRequest, result, error));
        lifecycle.postProcess(webRequest, result, error);
        lifecycle.afterCompletion(webRequest, result);
        awaitEvent("SERVLET_ASYNC_COMPLETED");
        assertNotNull(event("SERVLET_ASYNC_TIMEOUT"));
        assertNotNull(event("SERVLET_ASYNC_RESULT_ERROR"));
        assertEquals("IO", event("SERVLET_ASYNC_ERROR").at("/causes/0/category").asText());
        assertTrue(events.stream().allMatch(event -> "servlet-request".equals(event.path("requestId").asText())));
        assertFalse(events.toString().contains("private-disconnect-details"));
        assertFalse(result.hasResult());
    }

    @Test
    void embeddingEnvelopeRetryLogsSafeCategoryAndBatchContext() throws Exception {
        var transport = new com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagEmbeddingTransport("fixture-bge");
        var request = new org.springframework.mock.http.client.MockClientHttpRequest(org.springframework.http.HttpMethod.POST,
                java.net.URI.create("https://embedding.test/v1/embeddings"));
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        byte[] input = "{\"input\":[\"private-document\"]}".getBytes(StandardCharsets.UTF_8);
        try (var scope = ExecutionLog.current().with("startupId", "index-startup", "knowledgeBase", "productRagKnowledgeBase",
                "batchStart", 33, "batchEnd", 64).activate()) {
            transport.retryTemplate(3, 0).execute(context -> {
                try (var response = transport.intercept(request, input, (httpRequest, body) -> {
                    String output = attempts.incrementAndGet() == 1 ? "{\"error\":{\"message\":\"private-upstream-secret\"}}"
                            : "{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}";
                    return new org.springframework.mock.http.client.MockClientHttpResponse(output.getBytes(StandardCharsets.UTF_8),
                            org.springframework.http.HttpStatus.OK);
                })) {
                    assertEquals(200, response.getStatusCode().value());
                }
                return null;
            });
        }
        awaitEvent("RAG_EMBEDDING_RESPONSE");
        assertEquals(2, attempts.get());
        assertEquals("UNKNOWN_GATEWAY_ERROR", event("RAG_EMBEDDING_ERROR_ENVELOPE").path("category").asText());
        assertTrue(event("RAG_EMBEDDING_ERROR_ENVELOPE").path("retryable").asBoolean());
        assertEquals(33, event("RAG_EMBEDDING_RETRY").path("batchStart").asInt());
        assertEquals(0, event("RAG_EMBEDDING_RETRY").path("nextDelayMs").asInt());
        assertEquals(2, event("RAG_EMBEDDING_RESPONSE").path("attempt").asInt());
        assertEquals("index-startup", event("RAG_EMBEDDING_RESPONSE").path("startupId").asText());
        assertFalse(events.toString().contains("private-"));
    }

    @Test
    void asyncRedispatchDoesNotInstallAnotherLifecycleInterceptor() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        assertTrue(new SkillStreamLifecycleInterceptor().preHandle(request, new org.springframework.mock.web.MockHttpServletResponse(), new Object()));
        assertNull(org.springframework.web.context.request.async.WebAsyncUtils.getAsyncManager(request)
                .getDeferredResultInterceptor(SkillStreamLifecycleInterceptor.class.getName()));
    }

    @Test
    void correlatesModelToolsAndSummaryWithoutLoggingBusinessPayloads() throws InterruptedException {
        SkillEngine.Run run;
        try (var scope = ExecutionLog.current().with("requestId", "request-success").activate()) {
            var input = SkillProgressTests.input().deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) input).put("privateNote", "sensitive-input-marker");
            run = SkillProgressTests.engine(SkillEngineTests::scriptedResponse).execute("shopping-recommendation", input);
        }
        assertEquals("SUCCESS", run.status());
        awaitEvent("RUN_SUMMARY");
        assertEquals(6, events.stream().filter(event -> event.path("event").asText().equals("MODEL_RESPONSE")).count());
        assertEquals(5, events.stream().filter(event -> event.path("event").asText().equals("TOOL_EXECUTION_COMPLETED")).count());
        assertTrue(events.stream().allMatch(event -> run.runId().equals(event.path("runId").asText())));
        assertTrue(events.stream().allMatch(event -> "request-success".equals(event.path("requestId").asText())));
        assertTrue(event("MODEL_REQUEST").path("messageTextChars").asLong() > 0);
        assertTrue(event("MODEL_REQUEST").path("toolSchemaChars").asLong() > 0);
        assertTrue(events.stream().anyMatch(event -> "MODEL_REQUEST".equals(event.path("event").asText())
                && event.path("toolResponseChars").asLong() > 0));
        assertTrue(event("TOOL_EXECUTION_COMPLETED").hasNonNull("rawResultChars"));
        assertTrue(event("TOOL_EXECUTION_COMPLETED").hasNonNull("savedChars"));
        assertTrue(event("TOOL_EXECUTION_COMPLETED").hasNonNull("toolCallId"));
        assertEquals("SUCCESS", event("RUN_SUMMARY").path("status").asText());
        assertFalse(events.toString().contains("sensitive-input-marker"));
        assertFalse(events.toString().contains("xiaoming"));
        assertFalse(events.toString().contains("模型生成的测试分析"));
        ExecutionLog.current().info("AFTER_RUN");
        awaitEvent("AFTER_RUN");
        assertFalse(event("AFTER_RUN").has("runId"));
        assertFalse(event("AFTER_RUN").has("requestId"));
    }

    @Test
    void preservesSafeCauseFramesAndHttpStatusWithoutExceptionMessagesOrBodies() throws InterruptedException {
        var upstream = WebClientResponseException.create(429, "secret-status", HttpHeaders.EMPTY,
                "secret-body".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        var run = SkillProgressTests.engine(prompt -> { throw new IllegalStateException("Bearer secret-key", upstream); })
                .execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("FAILED", run.status());
        awaitEvent("RUN_SUMMARY");
        JsonNode error = event("MODEL_ERROR");
        assertEquals(1, error.path("round").asInt());
        assertEquals(429, error.path("causes").get(1).path("httpStatus").asInt());
        assertFalse(error.path("causes").get(0).path("frames").isEmpty());
        assertFalse(events.toString().contains("secret-"));
        assertEquals("FAILED", event("RUN_SUMMARY").path("status").asText());
    }

    @Test
    void isolatesConcurrentRunContexts() throws Exception {
        var engine = SkillProgressTests.engine(SkillEngineTests::scriptedResponse);
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (var scope = ExecutionLog.current().with("requestId", "concurrent-first").activate()) {
                return engine.execute("shopping-recommendation", SkillProgressTests.input());
            }
        });
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (var scope = ExecutionLog.current().with("requestId", "concurrent-second").activate()) {
                return engine.execute("shopping-recommendation", SkillProgressTests.input());
            }
        });
        var firstRun = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        var secondRun = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("SUCCESS", firstRun.status());
        assertEquals("SUCCESS", secondRun.status());
        ExecutionLog.current().info("CONCURRENT_RUNS_FINISHED");
        awaitEvent("CONCURRENT_RUNS_FINISHED");
        var expected = java.util.Map.of(firstRun.runId(), "concurrent-first", secondRun.runId(), "concurrent-second");
        assertEquals(2, events.stream().filter(event -> "RUN_SUMMARY".equals(event.path("event").asText())).count());
        events.stream().filter(event -> event.has("runId")).forEach(event ->
                assertEquals(expected.get(event.path("runId").asText()), event.path("requestId").asText()));
    }

    @Test
    void retainsOriginalValidationFailureLocation() throws InterruptedException {
        var run = SkillProgressTests.engine(prompt -> {
            if ("FINAL_ANSWER".equals(StandardSkillTests.facts(prompt).path("phase").asText())) {
                return SkillEngineTests.answer(SkillEngineTests.FINAL.replace("INSUFFICIENT_EVIDENCE", "FOUND"));
            }
            var response = SkillEngineTests.scriptedResponse(prompt);
            return response.hasToolCalls() ? response : SkillEngineTests.answer(SkillEngineTests.FINAL.replace("INSUFFICIENT_EVIDENCE", "FOUND"));
        }).execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("FAILED", run.status());
        awaitEvent("RUN_SUMMARY");
        assertTrue(event("RUN_ERROR").path("causes").size() > 1);
        assertTrue(event("RUN_ERROR").path("causes").toString().contains("ProductComparisonValidator"));
        assertNotNull(event("VALIDATION_STARTED"));
    }

    @Test
    void generationRecoveryLogsReasonAndBudgetWithoutModelText() throws InterruptedException {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var run = SkillProgressTests.engine(prompt -> {
            int round = calls.incrementAndGet();
            if (round < 6) return SkillEngineTests.scriptedResponse(prompt);
            return SkillEngineTests.answer(round == 6 ? "private repeated model text that must not enter diagnostic logs. ".repeat(60)
                    : SkillEngineTests.FINAL);
        }).execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("SUCCESS", run.status(), run.error());
        awaitEvent("RUN_SUMMARY");
        assertEquals("REPETITIVE_CONTENT", event("MODEL_OUTPUT_REJECTED").path("reason").asText());
        assertEquals(6, event("MODEL_GENERATION_RETRY").path("round").asInt());
        assertEquals(1, event("MODEL_GENERATION_RETRY").path("repairAttempt").asInt());
        assertEquals(2, event("MODEL_GENERATION_RETRY").path("remainingModelCalls").asInt());
        assertEquals("ANSWER", event("MODEL_GENERATION_RETRY").path("nextPhase").asText());
        assertFalse(events.toString().contains("private repeated model text"));
        assertEquals(5, run.traces().size());
    }

    @Test
    void recordsFailedToolIdentityAndDurationWithoutArguments() throws InterruptedException {
        var run = SkillProgressTests.engine(prompt -> prompt.getInstructions().size() == 2 ? SkillEngineTests.scriptedResponse(prompt)
                : SkillEngineTests.tool("bad-user", "get_user_profile", "{\"username\":\"xiaoyu\"}"))
                .execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("FAILED", run.status());
        awaitEvent("RUN_SUMMARY");
        JsonNode failure = event("TOOL_EXECUTION_FAILED");
        assertEquals("bad-user", failure.path("toolCallId").asText());
        assertEquals("get_user_profile", failure.path("tool").asText());
        assertEquals(2, failure.path("round").asInt());
        assertTrue(failure.hasNonNull("elapsedMs"));
        assertFalse(events.toString().contains("xiaoyu"));
    }

    @Test
    void carriesRequestAndRunIdsAcrossStreamingThreadsAndTimeout() throws Exception {
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Must stream"); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.interval(Duration.ofMillis(10)).map(index -> new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("").properties(java.util.Map.of("reasoning_content", "private-reasoning")).build()))));
            }
        };
        var response = controller(model).stream("shopping-recommendation", request());
        assertNotNull(response.getHeaders().getFirst("X-Request-Id"));
        response.getBody().blockLast(Duration.ofSeconds(5));
        awaitEvent("SSE_TERMINATED");
        String requestId = response.getHeaders().getFirst("X-Request-Id");
        String runId = event("RUN_CONFIG").path("runId").asText();
        assertTrue(events.stream().allMatch(event -> requestId.equals(event.path("requestId").asText())));
        assertEquals(runId, event("MODEL_WAITING").path("runId").asText());
        assertEquals(1, event("MODEL_WAITING").path("round").asInt());
        assertEquals(0, event("MODEL_WAITING").path("contentChars").asInt());
        assertTrue(event("MODEL_STREAM_STOPPED").path("reasoningChars").asLong() > 0);
        assertTrue(events.stream().anyMatch(event -> "MODEL_FIRST_REASONING".equals(event.path("event").asText())));
        assertEquals("FAILED", event("SSE_RESULT_ENQUEUED").path("status").asText());
        assertEquals("ON_COMPLETE", event("SSE_TERMINATED").path("signal").asText());
        assertTrue(events.stream().map(event -> event.path("thread").asText()).distinct().count() > 1);
        assertFalse(events.toString().contains("private-reasoning"));
    }

    @Test
    void distinguishesClientCancellationFromSuccessfulSseCompletion() throws Exception {
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Must stream"); }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.never(); }
        };
        controller(model).stream("shopping-recommendation", request()).getBody().take(2).blockLast(Duration.ofSeconds(5));
        awaitEvent("SSE_TERMINATED");
        awaitEvent("RUN_CANCELLED");
        awaitEvent("SSE_WORKER_CANCELLED");
        assertEquals("CANCEL", event("SSE_TERMINATED").path("signal").asText());
        assertTrue(events.stream().noneMatch(event -> "SSE_RESULT_ENQUEUED".equals(event.path("event").asText())));
    }

    @Test
    void correlatesRagIndexReuseAndRetrievalWithoutLoggingQueryOrDocuments() throws InterruptedException {
        var embedding = new AbstractEmbeddingModel() {
            public float[] embed(Document document) { return new float[] {1, 0}; }
            public EmbeddingResponse call(EmbeddingRequest request) {
                return new EmbeddingResponse(java.util.stream.IntStream.range(0, request.getInstructions().size())
                        .mapToObj(index -> new Embedding(new float[] {1, 0}, index)).toList());
            }
        };
        var resource = new ByteArrayResource(("# private-document\n\n" + "商品适合日常办公，规格需要核实。".repeat(20)).getBytes(StandardCharsets.UTF_8)) {
            public String getFilename() { return "private-document.md"; }
        };
        var rag = new RagKnowledgeBase(embedding, "test-embedding", () -> new Resource[] {resource}, 4, 0.1);
        try (var scope = ExecutionLog.current().with("runId", "rag-run", "round", 5, "toolCallId", "knowledge-call").activate()) {
            rag.ensureReady();
            rag.ensureReady();
            assertFalse(rag.retrieve("private-query", "private-document.md").sources().isEmpty());
        }
        awaitEvent("RAG_RETRIEVE_COMPLETED");
        assertEquals(1, events.stream().filter(event -> "RAG_INDEX_STARTED".equals(event.path("event").asText())).count());
        assertTrue(events.stream().anyMatch(event -> event.path("reused").asBoolean()));
        assertTrue(event("RAG_RETRIEVE_COMPLETED").path("hits").asInt() > 0);
        assertTrue(event("RAG_INDEX_COMPLETED").hasNonNull("indexVersion"));
        assertEquals(100, event("RAG_INDEX_PROGRESS").path("progressPercent").asInt());
        assertEquals(rag.status().chunks(), event("RAG_INDEX_PROGRESS").path("processedChunks").asInt());
        assertTrue(events.stream().allMatch(event -> "rag-run".equals(event.path("runId").asText())));
        assertTrue(events.stream().allMatch(event -> "knowledge-call".equals(event.path("toolCallId").asText())));
        assertFalse(events.toString().contains("private-query"));
        assertFalse(events.toString().contains("private-document"));
    }

    @Test
    void reportsCompletedIndexBatchesWithActualChunkCounts() throws InterruptedException {
        var embedding = new AbstractEmbeddingModel() {
            public float[] embed(Document document) { return new float[] {1, 0}; }
            public EmbeddingResponse call(EmbeddingRequest request) {
                return new EmbeddingResponse(java.util.stream.IntStream.range(0, request.getInstructions().size())
                        .mapToObj(index -> new Embedding(new float[] {1, 0}, index)).toList());
            }
        };
        Resource[] resources = java.util.stream.IntStream.range(0, 65).mapToObj(index ->
                new ByteArrayResource(("# 商品 " + index + "\n\n适合日常办公。").getBytes(StandardCharsets.UTF_8)) {
                    public String getFilename() { return "product-" + index + ".md"; }
                }).toArray(Resource[]::new);
        var rag = new RagKnowledgeBase(embedding, "test-embedding", () -> resources, 4, 0.1);
        rag.ensureReady();
        awaitEvent("RAG_INDEX_COMPLETED");
        var progress = events.stream().filter(event -> "RAG_INDEX_PROGRESS".equals(event.path("event").asText())).toList();
        assertEquals(java.util.List.of(32, 64, 65), progress.stream()
                .map(event -> event.path("processedChunks").asInt()).toList());
        assertTrue(progress.stream().allMatch(event -> event.path("totalChunks").asInt() == 65));
        assertEquals(100, progress.get(2).path("progressPercent").asInt());
        assertEquals(0, progress.get(2).path("estimatedRemainingMs").asLong());
        assertTrue(rag.status().ready());
    }

    @Test
    void restoresNestedContextAndEscapesLogControlCharacters() throws InterruptedException {
        try (var outer = ExecutionLog.current().with("requestId", "outer").activate()) {
            try (var inner = ExecutionLog.current().with("runId", "inner\nforged-line").activate()) {
                ExecutionLog.current().info("INNER");
            }
            ExecutionLog.current().info("OUTER");
        }
        ExecutionLog.current().info("CLEAN");
        awaitEvent("CLEAN");
        assertEquals("inner\nforged-line", event("INNER").path("runId").asText());
        assertFalse(event("OUTER").has("runId"));
        assertEquals("outer", event("OUTER").path("requestId").asText());
        assertFalse(event("CLEAN").has("requestId"));
    }

    @Test
    void directConsoleShowsStartupAndSafeFailuresEvenWhenPlatformLoggerIsOff() throws Exception {
        String output = System.getProperty("odin.diagnostics.output");
        var originalOut = System.out;
        var bytes = new java.io.ByteArrayOutputStream();
        try (var capture = new java.io.PrintStream(bytes, true, StandardCharsets.US_ASCII)) {
            System.setProperty("odin.diagnostics.output", "console");
            System.setOut(capture);
            logger.setLevel(Level.OFF);
            try (var scope = ExecutionLog.current().with("startupId", "direct-console-startup").activate()) {
                ExecutionLog.reportConfiguration();
                ExecutionLog.current().info("RAG_INDEX_PROGRESS", "processedChunks", 32, "totalChunks", 64);
                ExecutionLog.current().failure("RAG_EMBEDDING_FAILED", new IllegalStateException("private-api-key"));
            }
            var lines = bytes.toString(StandardCharsets.UTF_8).lines().filter(line -> line.contains("agent_diag ")).toList();
            assertEquals(3, lines.size());
            var configuration = SkillEngineTests.MAPPER.readTree(lines.get(0).substring(lines.get(0).indexOf("agent_diag ") + 11));
            assertEquals("AGENT_LOGGING_READY", configuration.path("event").asText());
            assertEquals("DIRECT_CONSOLE", configuration.path("diagnosticOutput").asText());
            assertTrue(configuration.path("infoEnabled").asBoolean());
            assertFalse(configuration.path("loggerInfoEnabled").asBoolean());
            assertEquals(ProcessHandle.current().pid(), configuration.path("pid").asLong());
            assertTrue(lines.get(1).contains("RAG_INDEX_PROGRESS"));
            assertTrue(lines.get(2).contains("WARN"));
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("private-api-key"));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("诊断日志直接输出"));
            assertFalse(events.stream().anyMatch(event -> "direct-console-startup".equals(event.path("startupId").asText())));
        } finally {
            System.setOut(originalOut);
            logger.setLevel(Level.INFO);
            if (output == null) System.clearProperty("odin.diagnostics.output");
            else System.setProperty("odin.diagnostics.output", output);
        }
    }

    @Test
    void directConsoleDoesNotAlsoSendDuplicateEventsToPlatformLogger() throws Exception {
        String output = System.getProperty("odin.diagnostics.output");
        var originalOut = System.out;
        var bytes = new java.io.ByteArrayOutputStream();
        try (var capture = new java.io.PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setProperty("odin.diagnostics.output", "console");
            System.setOut(capture);
            ExecutionLog.current().info("CONSOLE_ONCE");
            assertEquals(1, bytes.toString(StandardCharsets.UTF_8).lines().filter(line -> line.contains("CONSOLE_ONCE")).count());
            assertFalse(events.stream().anyMatch(event -> "CONSOLE_ONCE".equals(event.path("event").asText())));
        } finally {
            System.setOut(originalOut);
            if (output == null) System.clearProperty("odin.diagnostics.output");
            else System.setProperty("odin.diagnostics.output", output);
        }
    }

    private SkillController controller(ChatModel model) {
        var registry = SkillEngineTests.registry();
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, SkillEngineTests.agentCatalogResource(), registry);
        var client = new SkillModelClient(model, "test-model", Duration.ofSeconds(1), Duration.ofMillis(350), Duration.ofMillis(40));
        return new SkillController(catalog, new SkillEngine(SkillEngineTests.MAPPER, catalog, registry, client), registry);
    }

    private JsonNode request() {
        return SkillEngineTests.MAPPER.createObjectNode().set("input", SkillProgressTests.input());
    }

    private JsonNode event(String name) {
        return events.stream().filter(event -> name.equals(event.path("event").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少日志事件: " + name));
    }

    private void awaitEvent(String name) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (events.stream().anyMatch(event -> name.equals(event.path("event").asText()))) return;
            Thread.sleep(10);
        }
        fail("等待日志超时: " + name);
    }
}
