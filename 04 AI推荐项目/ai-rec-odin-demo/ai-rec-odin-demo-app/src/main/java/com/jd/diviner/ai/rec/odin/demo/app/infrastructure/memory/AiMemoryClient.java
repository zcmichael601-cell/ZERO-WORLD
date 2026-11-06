package com.jd.diviner.ai.rec.odin.demo.app.infrastructure.memory;

import com.jd.diviner.ai.rec.odin.demo.app.domain.user.UserIdentifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

public final class AiMemoryClient {
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;
    private final int limit;
    private final HttpClient client;

    public AiMemoryClient(ObjectMapper mapper, URI endpoint, Duration timeout, int limit) {
        if (!List.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null || endpoint.getQuery() != null) {
            throw new IllegalArgumentException("AI Memory endpoint 必须为不含凭证和查询参数的 HTTP(S) 地址");
        }
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0 || limit < 1 || limit > 30) {
            throw new IllegalArgumentException("AI Memory 超时须为 0~30 秒，limit 须为 1~30");
        }
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.limit = limit;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public JsonNode search(String userId, String type) {
        if (!List.of("long_term_memory", "origin_memory").contains(type)) throw new IllegalArgumentException("不支持的 Memory 类型");
        var body = mapper.createObjectNode().put("user_id", UserIdentifier.validate(userId)).put("query", "");
        var config = body.putObject("search_cfg").putObject(type).put("memory_type", type).put("limit", limit).put("vsearch", false);
        config.putObject("filters").put("role", "user");
        long start = System.nanoTime();
        var log = ExecutionLog.current().with("channel", "MEMORY", "memoryType", type);
        log.info("MEMORY_REQUEST_STARTED", "limit", limit, "timeoutMs", timeout.toMillis());
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var pending = client.sendAsync(request, response -> new LimitedBody());
        try {
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            log.info("MEMORY_HTTP_RESPONSE", "httpStatus", response.statusCode(), "elapsedMs", ExecutionLog.elapsed(start));
            if (response.statusCode() != 200) throw new IllegalStateException("AI Memory HTTP " + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            if (root == null || !root.isObject()) throw new IllegalStateException("AI Memory 响应不是对象");
            requireSuccess(root);
            JsonNode results = root.has("results") ? root.get("results") : root;
            if (!results.isObject() || !results.has(type)) throw new IllegalStateException("AI Memory 缺少请求的结果分组");
            JsonNode group = results.get(type);
            if (group.isObject()) requireSuccess(group);
            JsonNode memories = group.isArray() ? group : group.get("memories");
            if (memories == null || !memories.isArray()) throw new IllegalStateException("AI Memory memories 格式无效");
            if (memories.size() > limit) throw new IllegalStateException("AI Memory 返回条数超过请求限制");
            log.info("MEMORY_REQUEST_COMPLETED", "elapsedMs", ExecutionLog.elapsed(start), "records", memories.size());
            return memories;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.failure("MEMORY_REQUEST_FAILED", exception, "elapsedMs", ExecutionLog.elapsed(start));
            throw new IllegalStateException("AI Memory 请求被中断", exception);
        } catch (Exception exception) {
            log.failure("MEMORY_REQUEST_FAILED", exception, "elapsedMs", ExecutionLog.elapsed(start));
            throw new IllegalStateException("AI Memory 查询失败，请检查网络、响应格式及 MEMORY_REQUEST_FAILED 日志；未使用替代用户数据", exception);
        } finally {
            pending.cancel(true);
        }
    }

    private static void requireSuccess(JsonNode value) {
        if (value.has("code") && !"0".equals(value.path("code").asText())) {
            ExecutionLog.current().info("MEMORY_BUSINESS_REJECTED", "channel", "MEMORY",
                    "code", value.path("code").asText().matches("[0-9-]{1,12}") ? value.path("code").asText() : "INVALID");
            throw new IllegalStateException("AI Memory 业务状态异常");
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long bytes;
        private boolean failed;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            bytes += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (bytes > 1024 * 1024) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IllegalStateException("AI Memory 响应超过 1 MiB"));
            } else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
