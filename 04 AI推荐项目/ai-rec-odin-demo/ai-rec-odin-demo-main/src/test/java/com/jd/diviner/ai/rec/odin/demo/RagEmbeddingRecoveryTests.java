package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.bootstrap.RagConfiguration;
import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagEmbeddingTransport;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class RagEmbeddingRecoveryTests {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void retriesOnlyTheFailedEmbeddingRequestBeforePublishingTheIndex(int firstStatus) throws Exception {
        var requests = new AtomicInteger();
        var server = server(requests, firstStatus, "{\"error\":\"private-upstream-payload\"}", false);
        try {
            var knowledge = new RagConfiguration().knowledgeBase(environment(server), environment(server).getProperty("odin.rag.documents"), null);
            var status = knowledge.rebuild();
            assertTrue(status.ready());
            assertEquals(status.chunks() + 2, requests.get());
            int afterStartup = requests.get();
            knowledge.ensureReady();
            assertEquals(afterStartup, requests.get());
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    void doesNotRetryPermanentHttpErrorsAndDoesNotExposeTheirBody(int status) throws Exception {
        var requests = new AtomicInteger();
        var server = server(requests, status, "{\"error\":\"Bearer private-upstream-secret\"}", true);
        try {
            var knowledge = new RagConfiguration().knowledgeBase(environment(server), environment(server).getProperty("odin.rag.documents"), null);
            var failure = assertThrows(RuntimeException.class, knowledge::rebuild);
            assertEquals(1, requests.get());
            assertFalse(knowledge.status().ready());
            assertFalse(knowledge.status().indexing());
            assertTrue(RagEmbeddingTransport.failureSummary(failure).contains("HTTP " + status));
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                assertFalse(String.valueOf(cause.getMessage()).contains("private-upstream-secret"));
            }
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"data\":null}", "{\"data\":[]}", "not-json",
            "{\"data\":[{\"index\":0,\"embedding\":[]}]}"})
    void recoversMalformedSuccessfulResponsesWithoutNullPointerFailures(String body) throws Exception {
        var requests = new AtomicInteger();
        var server = server(requests, 200, body, false);
        try {
            var status = new RagConfiguration().knowledgeBase(environment(server), environment(server).getProperty("odin.rag.documents"), null).rebuild();
            assertTrue(status.ready());
            assertEquals(status.chunks() + 2, requests.get());
        } finally { server.stop(0); }
    }

    @Test
    void successfulHttpStatusWithPermanentErrorEnvelopeStillFailsWithoutRetry() throws Exception {
        var requests = new AtomicInteger();
        var server = server(requests, 200, "{\"error\":{\"code\":401,\"type\":\"authentication_error\",\"message\":\"private-upstream-secret\"}}", true);
        try {
            var knowledge = new RagConfiguration().knowledgeBase(environment(server), environment(server).getProperty("odin.rag.documents"), null);
            var failure = assertThrows(RuntimeException.class, knowledge::rebuild);
            assertEquals(1, requests.get());
            assertFalse(knowledge.status().ready());
            assertTrue(RagEmbeddingTransport.failureSummary(failure).contains("ERROR_ENVELOPE"));
            assertFalse(String.valueOf(failure.getMessage()).contains("private-upstream-secret"));
        } finally { server.stop(0); }
    }

    @Test
    void persistentMalformedResponsesAreBoundedAndLeaveNoPartialIndex() throws Exception {
        var requests = new AtomicInteger();
        var server = server(requests, 200, "{\"data\":null}", true);
        try {
            var knowledge = new RagConfiguration().knowledgeBase(environment(server), environment(server).getProperty("odin.rag.documents"), null);
            var failure = assertThrows(RuntimeException.class, knowledge::rebuild);
            assertEquals(3, requests.get());
            assertTrue(RagEmbeddingTransport.failureSummary(failure).contains("INVALID_DATA_COUNT"));
            assertFalse(knowledge.status().ready());
            assertFalse(knowledge.status().indexing());
            assertEquals(0, knowledge.status().chunks());
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"error\":{\"code\":\"rate_limit_exceeded\"}}",
            "{\"error\":{\"type\":\"server_error\"}}", "{\"error\":{\"code\":\"503\"}}",
            "{\"code\":1,\"error\":{\"code\":0,\"message\":\"private-upstream-secret\"}}",
            "{\"error\":{\"code\":null},\"code\":503}", "{\"error\":\"private-upstream-secret\"}",
            "{\"error\":{\"message\":\"private-upstream-secret\"}}", "{\"error\":{\"code\":\"BUSINESS_1001\"}}"})
    void retriesTransientOrUnknownBusinessEnvelopesOnTheSameRequest(String body) throws Exception {
        var requests = new AtomicInteger();
        var server = server(requests, 200, body, false);
        try {
            var status = new RagConfiguration().knowledgeBase(environment(server), environment(server).getProperty("odin.rag.documents"), null).rebuild();
            assertTrue(status.ready());
            assertEquals(status.chunks() + 2, requests.get());
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"error\":{\"code\":\"invalid_api_key\"}}",
            "{\"error\":{\"type\":\"authentication_error\"}}", "{\"error\":{\"code\":\"model_not_found\"}}",
            "{\"error\":{\"code\":\"insufficient_quota\"}}", "{\"error\":{\"code\":null},\"code\":403}",
            "{\"error\":\"Invalid API key: private-upstream-secret\"}",
            "{\"error\":{\"code\":503,\"type\":\"invalid_request_error\"}}"})
    void neverRetriesKnownPermanentBusinessErrors(String body) throws Exception {
        var requests = new AtomicInteger();
        var server = server(requests, 200, body, true);
        try {
            var knowledge = new RagConfiguration().knowledgeBase(environment(server), environment(server).getProperty("odin.rag.documents"), null);
            var failure = assertThrows(RuntimeException.class, knowledge::rebuild);
            assertEquals(1, requests.get());
            assertFalse(knowledge.status().ready());
            assertTrue(RagEmbeddingTransport.failureSummary(failure).contains("PERMANENT_GATEWAY_ERROR"));
            assertFalse(failure.getMessage().contains("private-upstream-secret"));
        } finally { server.stop(0); }
    }

    @Test
    void persistentUnknownEnvelopeStopsAtBudgetWithoutPublishingPartialIndex() throws Exception {
        var requests = new AtomicInteger();
        var server = server(requests, 200, "{\"error\":\"private-upstream-secret\"}", true);
        try {
            var knowledge = new RagConfiguration().knowledgeBase(environment(server), environment(server).getProperty("odin.rag.documents"), null);
            var failure = assertThrows(RuntimeException.class, knowledge::rebuild);
            assertEquals(3, requests.get());
            assertTrue(RagEmbeddingTransport.failureSummary(failure).contains("UNKNOWN_GATEWAY_ERROR"));
            assertFalse(knowledge.status().ready());
            assertFalse(knowledge.status().indexing());
            assertEquals(0, knowledge.status().chunks());
        } finally { server.stop(0); }
    }

    private HttpServer server(AtomicInteger requests, int firstStatus, String failureBody, boolean alwaysFail) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            mapper.readTree(exchange.getRequestBody());
            int count = requests.incrementAndGet();
            boolean fail = alwaysFail || count == 1;
            byte[] bytes = fail ? failureBody.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : mapper.writeValueAsBytes(Map.of("object", "list", "data", List.of(Map.of("object", "embedding", "index", 0,
                            "embedding", List.of(1.0, 0.5, 0.1)))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(fail ? firstStatus : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private MockEnvironment environment(HttpServer server) throws Exception {
        Files.writeString(directory.resolve("bag.md"), "# 背包说明\n\n背包仅能防泼水，不可浸泡，雨天需要独立的防水袋保护电脑。");
        return new MockEnvironment().withProperty("dong.spring.ai.joyai.embedding.base-url", "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty("dong.spring.ai.joyai.embedding.api-key", "fixture-key")
                .withProperty("dong.spring.ai.joyai.embedding.options.model", "fixture-bge")
                .withProperty("odin.rag.documents", directory.toUri() + "*.md")
                .withProperty("odin.rag.embedding.retry-delay-millis", "0");
    }
}
