package com.jd.diviner.ai.rec.odin.demo.app.bootstrap;

import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillRetryPolicy;

import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:/profile/ai.properties")
@PropertySource("classpath:/skills/runtime.properties")
public class SkillModelConfiguration {

    @Bean
    public SkillModelClient skillModelClient(Environment environment) {
        String prefix = "dong.spring.ai.joyai.chat.";
        String apiKey = environment.getProperty(prefix + "api-key", environment.getProperty("dong.spring.ai.joyai.api-key"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少现有 JoyAI API Key 配置，Skill 不会降级为 Mock 模型");
        }
        String model = environment.getRequiredProperty(prefix + "options.model");
        boolean streamToolCalls = environment.getProperty("odin.skills.model.stream-tool-calls", Boolean.class,
                !model.toLowerCase(java.util.Locale.ROOT).startsWith("joyai-"));
        Duration idleTimeout = Duration.ofSeconds(environment.getProperty("odin.skills.model.idle-timeout-seconds", Long.class, SkillModelClient.DEFAULT_IDLE_TIMEOUT.toSeconds()));
        Duration roundTimeout = Duration.ofSeconds(environment.getProperty("odin.skills.model.round-timeout-seconds", Long.class, SkillModelClient.DEFAULT_ROUND_TIMEOUT.toSeconds()));
        Duration runTimeout = Duration.ofSeconds(environment.getProperty("odin.skills.run-timeout-seconds", Long.class, SkillModelClient.DEFAULT_RUN_TIMEOUT.toSeconds()));
        Duration progressInterval = Duration.ofSeconds(environment.getProperty("odin.skills.model.progress-interval-seconds", Long.class, 5L));
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(roundTimeout);
        JdkClientHttpConnector connector = new JdkClientHttpConnector(httpClient);
        connector.setReadTimeout(roundTimeout);
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(environment.getRequiredProperty(prefix + "base-url"))
                .completionsPath(environment.getRequiredProperty(prefix + "completions-path"))
                .apiKey(apiKey).restClientBuilder(RestClient.builder().requestFactory(factory))
                .webClientBuilder(WebClient.builder().clientConnector(connector)).build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.2)
                        .internalToolExecutionEnabled(false).parallelToolCalls(false).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
        SkillRetryPolicy retry = new SkillRetryPolicy(environment.getProperty("odin.skills.model.max-attempts", Integer.class, 3),
                Duration.ofMillis(environment.getProperty("odin.skills.model.retry-delay-millis", Long.class, 1000L)));
        SkillModelClient client = new SkillModelClient(chatModel, model, idleTimeout, roundTimeout, progressInterval, retry, runTimeout, streamToolCalls);
        ExecutionLog.current().info("MODEL_CLIENT_CONFIGURED", "channel", "MODEL", "model", model,
                "connectTimeoutMs", 10_000, "idleTimeoutMs", idleTimeout.toMillis(),
                "roundTimeoutMs", roundTimeout.toMillis(), "runTimeoutMs", runTimeout.toMillis(),
                "httpReadTimeoutMs", roundTimeout.toMillis(), "progressIntervalMs", progressInterval.toMillis(),
                "maxAttempts", retry.maxAttempts(), "retryDelayMs", retry.initialDelay().toMillis(), "streamToolCalls", streamToolCalls);
        return client;
    }
}
