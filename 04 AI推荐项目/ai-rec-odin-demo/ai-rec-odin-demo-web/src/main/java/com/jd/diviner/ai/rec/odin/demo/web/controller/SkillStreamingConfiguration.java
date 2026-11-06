package com.jd.diviner.ai.rec.odin.demo.web.controller;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class SkillStreamingConfiguration implements WebMvcConfigurer {

    @org.springframework.beans.factory.annotation.Value("${odin.skills.run-timeout-seconds:1200}")
    private long runTimeoutSeconds = 1200;

    @org.springframework.context.annotation.Bean
    public SkillStreamSessions skillStreamSessions(com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine engine,
                                                    com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new SkillStreamSessions(engine, mapper);
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(java.time.Duration.ofSeconds(runTimeoutSeconds).plusSeconds(60).toMillis());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SkillStreamLifecycleInterceptor()).addPathPatterns("/api/skills/*/stream", "/api/skills/*/session-stream");
    }
}
