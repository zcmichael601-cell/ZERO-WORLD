package com.jd.diviner.ai.rec.odin.demo.app.bootstrap;

import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillTimingHistory;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillExtension;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillExtensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagKnowledgeBase;
import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLogConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;

@Configuration(proxyBeanMethods = false)
@Import({SkillModelConfiguration.class, RagConfiguration.class, ExecutionLogConfiguration.class, ToolConfiguration.class})
public class SkillConfiguration {

    @Bean
    public SkillExtensions skillExtensions(org.springframework.beans.factory.ObjectProvider<SkillExtension> beans) {
        var extensions = SkillExtensions.discovered();
        extensions.addAll(beans.orderedStream().toList());
        return new SkillExtensions(extensions);
    }

    @Bean
    public SkillCatalog skillCatalog(ObjectMapper mapper, ResourceLoader loader, ToolRegistry registry,
                                     SkillExtensions extensions,
                                     @Value("${odin.skills.location:classpath:skills/recommendation.json}") String location) {
        if (!location.startsWith("classpath:") && !location.startsWith("file:")) {
            throw new IllegalArgumentException("odin.skills.location 仅支持 classpath: 或 file: 本地资源");
        }
        return new SkillCatalog(mapper, loader.getResource(location), registry, extensions);
    }

    @Bean
    public SkillTimingHistory skillTimingHistory(@Qualifier("productRagKnowledgeBase") RagKnowledgeBase knowledgeBase) {
        return new SkillTimingHistory(() -> knowledgeBase.status().ready());
    }

    @Bean
    public SkillEngine skillEngine(ObjectMapper mapper, SkillCatalog catalog, ToolRegistry registry, SkillModelClient model,
                                   SkillTimingHistory timingHistory) {
        return new SkillEngine(mapper, catalog, registry, model, timingHistory);
    }
}
