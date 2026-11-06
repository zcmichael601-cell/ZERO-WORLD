package com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ExecutionLogConfiguration {

    @Bean
    @org.springframework.context.annotation.Lazy(false)
    public InitializingBean agentLoggingStartupReporter() {
        return ExecutionLog::reportConfiguration;
    }
}
