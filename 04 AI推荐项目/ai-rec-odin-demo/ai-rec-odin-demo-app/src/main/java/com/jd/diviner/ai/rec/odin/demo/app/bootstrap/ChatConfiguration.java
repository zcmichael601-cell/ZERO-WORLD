package com.jd.diviner.ai.rec.odin.demo.app.bootstrap;

import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.application.chat.ChatService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration(proxyBeanMethods = false)
public class ChatConfiguration {
    @Bean
    public ChatService chatService(SkillModelClient model,
                                   @Value("classpath:prompts/chat-system.txt") Resource prompt) throws IOException {
        var memory = MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(10).build();
        var client = ChatClient.builder(model.chatModel())
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build();
        return new ChatService(client, prompt.getContentAsString(StandardCharsets.UTF_8));
    }
}
