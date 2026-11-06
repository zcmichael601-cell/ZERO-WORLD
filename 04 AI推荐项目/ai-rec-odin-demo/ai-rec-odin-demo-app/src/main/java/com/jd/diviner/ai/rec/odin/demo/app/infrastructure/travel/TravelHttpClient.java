package com.jd.diviner.ai.rec.odin.demo.app.infrastructure.travel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

public final class TravelHttpClient implements TravelTransport {

    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Override
    public String get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "OdinSkillTravelDemo/1.0")
                .header("Accept", "application/json,text/html").GET().build();
        var pending = client.sendAsync(request, response -> new LimitedBody());
        try {
            HttpResponse<byte[]> response = pending.get(20, TimeUnit.SECONDS);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("旅游数据源 HTTP " + response.statusCode());
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("旅游数据源请求被中断", exception);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException("旅游数据源请求超时、响应过大或网络不可用", exception);
        } finally {
            pending.cancel(true);
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long bytes;
        private boolean failed;

        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }

        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            bytes += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (bytes > MAX_BYTES) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IllegalStateException("旅游数据响应超过 2 MiB"));
            } else {
                delegate.onNext(buffers);
            }
        }

        public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
