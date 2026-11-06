package com.jd.diviner.ai.rec.odin.demo.app.bootstrap;

import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagEmbeddingTransport;
import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagKnowledgeBase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.domain.product.JdProductCatalog;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@Import(SkillModelConfiguration.class)
public class RagConfiguration {

    @Bean
    @org.springframework.context.annotation.Lazy(false)
    public RagStartupLifecycle ragStartupLifecycle(Map<String, RagKnowledgeBase> knowledgeBases, ConfigurableApplicationContext context) {
        return new RagStartupLifecycle(knowledgeBases, context);
    }

    @Bean
    public JdProductCatalog jdProductCatalog(ObjectMapper mapper, ResourceLoader loader, Environment environment) {
        String location = environment.getProperty("odin.products.location", "classpath:catalog/jd-products.json");
        if (!location.startsWith("classpath:") && !location.startsWith("file:")) throw new IllegalArgumentException("商品目录仅支持本地 classpath: 或 file: 资源");
        return new JdProductCatalog(mapper, loader.getResource(location), java.time.Clock.systemUTC(),
                Duration.ofHours(environment.getProperty("odin.products.max-age-hours", Long.class, 168L)));
    }

    @Bean
    public RagKnowledgeBase productRagKnowledgeBase(Environment environment, JdProductCatalog catalog) {
        return knowledgeBase(environment, null, catalog);
    }

    public RagKnowledgeBase knowledgeBase(Environment environment, String location, JdProductCatalog catalog) {
        String prefix = "dong.spring.ai.joyai.embedding.";
        String apiKey = environment.getProperty(prefix + "api-key", environment.getProperty("dong.spring.ai.joyai.api-key"));
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("缺少现有 Embedding API Key 配置");
        String modelName = environment.getRequiredProperty(prefix + "options.model");
        var transport = new RagEmbeddingTransport(modelName);
        int attempts = environment.getProperty("odin.rag.embedding.max-attempts", Integer.class, 3);
        long delayMillis = environment.getProperty("odin.rag.embedding.retry-delay-millis", Long.class, 1000L);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        var api = OpenAiApi.builder().apiKey(apiKey)
                .baseUrl(environment.getRequiredProperty(prefix + "base-url"))
                .embeddingsPath(environment.getProperty(prefix + "embeddings-path", "/v1/embeddings"))
                .restClientBuilder(RestClient.builder().requestFactory(factory).requestInterceptor(transport)).build();
        var embedding = new OpenAiEmbeddingModel(api, MetadataMode.NONE,
                OpenAiEmbeddingOptions.builder().model(modelName).build(), transport.retryTemplate(attempts, delayMillis));
        RagKnowledgeBase.ResourceSource source;
        if (location != null) {
            if (!location.startsWith("classpath:") && !location.startsWith("classpath*:") && !location.startsWith("file:")) throw new IllegalArgumentException("RAG 资料只支持本地资源");
            source = () -> new PathMatchingResourcePatternResolver().getResources(location);
        } else {
            source = catalog::documents;
        }
        return new RagKnowledgeBase(embedding, modelName, source,
                environment.getProperty("odin.rag.top-k", Integer.class, 4),
                environment.getProperty("odin.rag.similarity-threshold", Double.class, 0.5));
    }

}
