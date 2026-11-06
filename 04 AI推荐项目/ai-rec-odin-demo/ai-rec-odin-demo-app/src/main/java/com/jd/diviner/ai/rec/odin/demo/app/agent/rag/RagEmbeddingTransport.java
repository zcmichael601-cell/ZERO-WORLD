package com.jd.diviner.ai.rec.odin.demo.app.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClientResponseException;

public final class RagEmbeddingTransport implements ClientHttpRequestInterceptor {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String model;

    public RagEmbeddingTransport(String model) {
        this.model = model;
    }

    public RetryTemplate retryTemplate(int maxAttempts, long delayMillis) {
        if (maxAttempts < 1 || maxAttempts > 5 || delayMillis < 0 || delayMillis > 10000) {
            throw new IllegalArgumentException("RAG 向量重试次数需为 1~5，初始退避需为 0~10000 毫秒");
        }
        var builder = RetryTemplate.builder().maxAttempts(maxAttempts).retryOn(RagEmbeddingTransport::retryable);
        if (delayMillis == 0) builder.noBackoff();
        else builder.exponentialBackoff(delayMillis, 2, Math.max(delayMillis, 10000));
        return builder.withListener(new RetryListener() {
            @Override
            public <Result, Failure extends Throwable> void onError(RetryContext context, RetryCallback<Result, Failure> callback, Throwable error) {
                boolean retry = context.getRetryCount() < maxAttempts && retryable(error);
                ExecutionLog.current().failure(retry ? "RAG_EMBEDDING_RETRY" : "RAG_EMBEDDING_FAILED", error,
                        "embeddingModel", model, "attempt", context.getRetryCount(), "maxAttempts", maxAttempts,
                        "retryable", retry, "nextDelayMs", retry ? Math.min(10000L, delayMillis * (1L << (context.getRetryCount() - 1))) : 0L,
                        "failureSummary", failureSummary(error));
            }
        }).build();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        long start = System.nanoTime();
        RetryContext retry = RetrySynchronizationManager.getContext();
        int attempt = retry == null ? 1 : retry.getRetryCount() + 1;
        JsonNode input = mapper.readTree(body).path("input");
        int expected = input.isArray() ? input.size() : 1;
        ExecutionLog log = ExecutionLog.current();
        log.info("RAG_EMBEDDING_REQUEST", "embeddingModel", model, "attempt", attempt, "inputCount", expected, "requestBytes", body.length,
                "endpointHost", request.getURI().getHost(), "endpointPath", request.getURI().getPath());
        ClientHttpResponse response = execution.execute(request, body);
        try {
            int status = response.getStatusCode().value();
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.info("RAG_EMBEDDING_HTTP_ERROR", "httpStatus", status, "attempt", attempt, "elapsedMs", ExecutionLog.elapsed(start));
                throw new RestClientResponseException("Embedding HTTP " + status, status, "", HttpHeaders.EMPTY,
                        new byte[0], java.nio.charset.StandardCharsets.UTF_8);
            }
            byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new InvalidResponse("RESPONSE_TOO_LARGE", false);
            JsonNode root;
            try {
                root = mapper.readTree(bytes);
            } catch (IOException exception) {
                throw new InvalidResponse("INVALID_JSON", true);
            }
            if (root == null) throw new InvalidResponse("EMPTY_BODY", true);
            if (root.hasNonNull("error")) {
                ErrorClassification classification = classifyError(root);
                log.info("RAG_EMBEDDING_ERROR_ENVELOPE", "httpStatus", status, "attempt", attempt,
                        "elapsedMs", ExecutionLog.elapsed(start), "responseBytes", bytes.length,
                        "upstreamCode", diagnosticCode(root.path("error").path("code")),
                        "upstreamRootCode", diagnosticCode(root.path("code")),
                        "upstreamType", diagnosticCode(root.path("error").path("type")),
                        "category", classification.category(), "retryable", classification.retryable());
                throw new InvalidResponse("ERROR_ENVELOPE", classification.retryable(), classification.category());
            }
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != expected) throw new InvalidResponse("INVALID_DATA_COUNT", true);
            var indexes = new HashSet<Integer>();
            int dimensions = -1;
            for (JsonNode item : data) {
                JsonNode index = item.path("index");
                if (!index.isIntegralNumber() || index.asInt() < 0 || index.asInt() >= expected || !indexes.add(index.asInt())) {
                    throw new InvalidResponse("INVALID_VECTOR_INDEX", true);
                }
                JsonNode vector = item.path("embedding");
                if (!vector.isArray() || vector.isEmpty() || (dimensions >= 0 && vector.size() != dimensions)) {
                    throw new InvalidResponse("INVALID_VECTOR_DIMENSIONS", true);
                }
                dimensions = vector.size();
                for (JsonNode value : vector) {
                    if (!value.isNumber() || !Float.isFinite((float) value.asDouble())) throw new InvalidResponse("INVALID_VECTOR_VALUE", true);
                }
            }
            log.info("RAG_EMBEDDING_RESPONSE", "httpStatus", status, "attempt", attempt, "elapsedMs", ExecutionLog.elapsed(start),
                    "vectors", data.size(), "dimensions", dimensions, "responseBytes", bytes.length);
            return new ClientHttpResponse() {
                public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                public String getStatusText() throws IOException { return response.getStatusText(); }
                public HttpHeaders getHeaders() { return response.getHeaders(); }
                public InputStream getBody() { return new ByteArrayInputStream(bytes); }
                public void close() { response.close(); }
            };
        } catch (IOException | RuntimeException exception) {
            response.close();
            throw exception;
        }
    }

    private static String diagnosticCode(JsonNode value) {
        String code = value.asText("");
        return code.matches("[A-Za-z0-9_.-]{1,64}") ? code : "UNAVAILABLE";
    }

    private record ErrorClassification(String category, boolean retryable) {}

    private static ErrorClassification classifyError(JsonNode root) {
        JsonNode error = root.path("error");
        var codes = Set.of("authentication_error", "invalid_api_key", "unauthorized", "permission_denied",
                "permission_error", "forbidden", "invalid_request_error", "invalid_request", "bad_request",
                "model_not_found", "not_found", "insufficient_quota", "quota_exceeded", "context_length_exceeded");
        var transientCodes = Set.of("server_error", "internal_error", "internal_server_error", "service_unavailable",
                "temporarily_unavailable", "overloaded_error", "overloaded", "rate_limit_error", "rate_limit_exceeded",
                "too_many_requests", "timeout", "request_timeout", "gateway_timeout");
        boolean temporary = false;
        for (JsonNode value : java.util.List.of(error.path("code"), error.path("type"), error.path("status"),
                error.path("status_code"), root.path("code"), root.path("status"), root.path("type"), error)) {
            String code = value.asText("").toLowerCase(Locale.ROOT).strip();
            if (codes.contains(code)) return new ErrorClassification("PERMANENT_GATEWAY_ERROR", false);
            int status = value.asInt(0);
            if (status >= 400 && status < 500 && !retryableStatus(status)) {
                return new ErrorClassification("PERMANENT_GATEWAY_ERROR", false);
            }
            temporary |= transientCodes.contains(code) || retryableStatus(status);
        }
        String message = (error.isTextual() ? error.asText() : error.path("message").asText(root.path("message").asText("")))
                .toLowerCase(Locale.ROOT);
        if (message.contains("invalid api key") || message.contains("incorrect api key") || message.contains("invalid_api_key")
                || message.contains("authentication failed") || message.contains("permission denied") || message.contains("insufficient quota")) {
            return new ErrorClassification("PERMANENT_GATEWAY_ERROR", false);
        }
        return new ErrorClassification(temporary ? "TRANSIENT_GATEWAY_ERROR" : "UNKNOWN_GATEWAY_ERROR", true);
    }

    private static boolean retryableStatus(int status) {
        return status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static boolean retryable(Throwable error) {
        if (Thread.currentThread().isInterrupted()) return false;
        boolean networkError = false;
        for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause()) {
            if (error instanceof InterruptedException || error instanceof CancellationException) return false;
            if (error instanceof RestClientResponseException response) return retryableStatus(response.getStatusCode().value());
            if (error instanceof InvalidResponse response) return response.retryable;
            if (error instanceof IOException) networkError = true;
        }
        return networkError;
    }

    public static String failureSummary(Throwable error) {
        var summary = new StringBuilder();
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (!summary.isEmpty()) summary.append(" -> ");
            summary.append(error.getClass().getSimpleName());
            if (error instanceof RestClientResponseException response) summary.append("[HTTP ").append(response.getStatusCode().value()).append(']');
            if (error instanceof InvalidResponse response) {
                summary.append('[').append(response.code).append(']');
                if (response.category != null) summary.append('[').append(response.category).append(']');
            }
        }
        return summary.toString();
    }

    public static final class InvalidResponse extends IllegalStateException {
        private final String code;
        private final boolean retryable;
        private final String category;

        private InvalidResponse(String code, boolean retryable) {
            this(code, retryable, null);
        }

        private InvalidResponse(String code, boolean retryable, String category) {
            super("Embedding 响应协议错误：" + code + (category == null ? "" : "，分类=" + category));
            this.code = code;
            this.retryable = retryable;
            this.category = category;
        }
    }
}
