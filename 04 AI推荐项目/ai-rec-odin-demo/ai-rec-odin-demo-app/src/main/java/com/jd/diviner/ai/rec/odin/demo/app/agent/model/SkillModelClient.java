package com.jd.diviner.ai.rec.odin.demo.app.agent.model;

import org.springframework.ai.chat.model.ChatModel;
import java.time.Duration;

public record SkillModelClient(ChatModel chatModel, String modelName, Duration idleTimeout,
                               Duration roundTimeout, Duration progressInterval, SkillRetryPolicy retryPolicy, Duration runTimeout,
                               boolean streamToolCalls) {
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(180);
    public static final Duration DEFAULT_ROUND_TIMEOUT = Duration.ofSeconds(600);
    public static final Duration DEFAULT_RUN_TIMEOUT = Duration.ofSeconds(1200);

    public SkillModelClient(ChatModel chatModel, String modelName) {
        this(chatModel, modelName, DEFAULT_IDLE_TIMEOUT, DEFAULT_ROUND_TIMEOUT, Duration.ofSeconds(5));
    }

    public SkillModelClient(ChatModel chatModel, String modelName, Duration idleTimeout,
                            Duration roundTimeout, Duration progressInterval) {
        this(chatModel, modelName, idleTimeout, roundTimeout, progressInterval, SkillRetryPolicy.disabled());
    }

    public SkillModelClient(ChatModel chatModel, String modelName, Duration idleTimeout,
                            Duration roundTimeout, Duration progressInterval, SkillRetryPolicy retryPolicy) {
        this(chatModel, modelName, idleTimeout, roundTimeout, progressInterval, retryPolicy, DEFAULT_RUN_TIMEOUT);
    }

    public SkillModelClient(ChatModel chatModel, String modelName, Duration idleTimeout,
                            Duration roundTimeout, Duration progressInterval, SkillRetryPolicy retryPolicy, Duration runTimeout) {
        this(chatModel, modelName, idleTimeout, roundTimeout, progressInterval, retryPolicy, runTimeout, true);
    }

    public SkillModelClient {
        java.util.Objects.requireNonNull(retryPolicy);
        if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero() || idleTimeout.compareTo(Duration.ofSeconds(600)) > 0
                || roundTimeout == null || roundTimeout.isNegative() || roundTimeout.isZero() || roundTimeout.compareTo(Duration.ofSeconds(1800)) > 0
                || runTimeout == null || runTimeout.isNegative() || runTimeout.isZero() || runTimeout.compareTo(Duration.ofSeconds(3600)) > 0
                || progressInterval == null || progressInterval.isNegative() || progressInterval.isZero()
                || progressInterval.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("模型空闲超时需为 (0,600] 秒、单轮超时 (0,1800] 秒、Skill 总时限 (0,3600] 秒、进度间隔 (0,30] 秒");
        }
    }
}
