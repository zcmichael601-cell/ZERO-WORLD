package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ConsoleLoggingBootstrap;
import com.jd.diviner.ai.rec.odin.demo.app.bootstrap.SkillConfiguration;
import com.jd.diviner.ai.rec.odin.demo.app.bootstrap.RagConfiguration;
import com.jd.diviner.ai.rec.odin.demo.web.controller.ChatController;
import com.jd.diviner.ai.rec.odin.demo.web.controller.WorkspaceController;
import com.jd.diviner.ai.rec.odin.demo.app.bootstrap.ChatConfiguration;
import com.jd.diviner.ai.rec.odin.demo.web.controller.SessionInterestController;
import com.jd.diviner.ai.rec.odin.demo.web.controller.SkillController;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({SkillConfiguration.class, SkillController.class, SessionInterestController.class, RagConfiguration.class, ChatConfiguration.class, ChatController.class, WorkspaceController.class})
@ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class, ServletWebServerFactoryAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class})
public class AiRecOdinDemoApplication {

    public static void main(String[] args) {
        ConsoleLoggingBootstrap.initialize();
        SpringApplication application = new SpringApplication(AiRecOdinDemoApplication.class);
        application.setDefaultProperties(Map.of("server.port", "8080", "server.address", "127.0.0.1",
                "spring.application.name", "odin-workspace",
                "logging.config", "classpath:logging/skill-demo-log4j2.xml"));
        application.run(args);
    }
}
