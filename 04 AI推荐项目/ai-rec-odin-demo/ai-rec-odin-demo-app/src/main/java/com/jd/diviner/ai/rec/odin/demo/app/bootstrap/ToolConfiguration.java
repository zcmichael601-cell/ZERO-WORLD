package com.jd.diviner.ai.rec.odin.demo.app.bootstrap;

import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import com.jd.diviner.ai.rec.odin.demo.app.application.jevcid.JevCidConfiguration;
import com.jd.diviner.ai.rec.odin.demo.app.domain.product.JdProductCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.memory.AiMemoryClient;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.travel.TravelHttpClient;
import com.jd.diviner.ai.rec.odin.demo.app.tools.product.DeepProductSearchTool;
import com.jd.diviner.ai.rec.odin.demo.app.tools.product.JdSearchProductsTool;
import com.jd.diviner.ai.rec.odin.demo.app.tools.product.ProductKnowledgeTool;
import com.jd.diviner.ai.rec.odin.demo.app.tools.travel.BeijingTravelTools;
import com.jd.diviner.ai.rec.odin.demo.app.tools.user.AiMemoryUserTools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagKnowledgeBase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(JevCidConfiguration.class)
@org.springframework.context.annotation.PropertySource("classpath:/skills/ai-memory.properties")
public class ToolConfiguration {
    @Bean
    public SkillTool productKnowledgeTool(ObjectMapper mapper,
                                          @Qualifier("productRagKnowledgeBase") RagKnowledgeBase knowledgeBase, JdProductCatalog catalog) {
        return new ProductKnowledgeTool(mapper, knowledgeBase, catalog);
    }

    @Bean
    public BeijingTravelTools beijingTravelTools(ObjectMapper mapper) {
        return new BeijingTravelTools(mapper, new TravelHttpClient(), java.time.Clock.systemUTC());
    }

    @Bean
    public SkillTool beijingWeatherTool(BeijingTravelTools tools) {
        return tools.weather();
    }

    @Bean
    public SkillTool beijingHotAttractionsTool(BeijingTravelTools tools) {
        return tools.attractions();
    }

    @Bean
    public AiMemoryClient aiMemoryClient(ObjectMapper mapper, org.springframework.core.env.Environment environment) {
        return new AiMemoryClient(mapper, java.net.URI.create(environment.getProperty("odin.memory.endpoint", "http://ai-memory.jd.local/search")),
                java.time.Duration.ofMillis(environment.getProperty("odin.memory.timeout-ms", Long.class, 10000L)),
                environment.getProperty("odin.memory.limit", Integer.class, 20));
    }

    @Bean
    public AiMemoryUserTools aiMemoryUserTools(ObjectMapper mapper, AiMemoryClient client) {
        return new AiMemoryUserTools(mapper, client);
    }

    @Bean
    public SkillTool userProfileTool(AiMemoryUserTools tools) {
        return tools.profile();
    }

    @Bean
    public SkillTool userBehaviorsTool(AiMemoryUserTools tools) {
        return tools.behaviors();
    }

    @Bean
    public SkillTool searchProductsTool(ObjectMapper mapper, JdProductCatalog catalog) {
        return new JdSearchProductsTool(mapper, catalog);
    }

    @Bean
    public ToolRegistry skillToolRegistry(List<SkillTool> tools) {
        return new ToolRegistry(tools);
    }

    @Bean
    public SkillTool deepProductSearchTool(ObjectMapper mapper, JdProductCatalog catalog,
                                           @Qualifier("productRagKnowledgeBase") RagKnowledgeBase knowledgeBase) {
        return new DeepProductSearchTool(mapper, catalog, knowledgeBase);
    }

    @Bean
    public SkillTool deepProductKnowledgeTool(ObjectMapper mapper, JdProductCatalog catalog,
                                              @Qualifier("productRagKnowledgeBase") RagKnowledgeBase knowledgeBase) {
        return new ProductKnowledgeTool(mapper, knowledgeBase, catalog, true);
    }

}
