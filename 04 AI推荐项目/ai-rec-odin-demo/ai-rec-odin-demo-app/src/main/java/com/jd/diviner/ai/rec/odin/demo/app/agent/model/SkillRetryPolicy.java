package com.jd.diviner.ai.rec.odin.demo.app.agent.model;

import java.time.Duration;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

public record SkillRetryPolicy(int maxAttempts, Duration initialDelay) {
    public SkillRetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 3 || initialDelay == null || initialDelay.isNegative()
                || initialDelay.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("模型尝试次数需为1~3，重试初始等待需为0~5秒");
        }
    }

    public static SkillRetryPolicy disabled() { return new SkillRetryPolicy(1, Duration.ZERO); }

    public long delayMillis(int failedAttempt) {
        return initialDelay.toMillis() * (1L << (failedAttempt - 1));
    }

    public boolean transientFailure(Throwable error) {
        boolean transientCause = false;
        for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause()) {
            if (error instanceof java.util.concurrent.CancellationException || error instanceof InterruptedException
                    || error instanceof com.fasterxml.jackson.core.JsonProcessingException) return false;
            int status = error instanceof RestClientResponseException response ? response.getStatusCode().value()
                    : error instanceof WebClientResponseException response ? response.getStatusCode().value() : 0;
            if (status != 0) return status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
            if (error instanceof java.io.IOException || error instanceof java.util.concurrent.TimeoutException) transientCause = true;
        }
        return transientCause;
    }
}
