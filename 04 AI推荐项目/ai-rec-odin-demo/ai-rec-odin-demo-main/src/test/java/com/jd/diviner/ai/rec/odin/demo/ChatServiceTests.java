package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.bootstrap.ChatConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;

class ChatServiceTests {
    @Test
    void usesConfiguredModelAndPromptAndKeepsConversationMemoryIsolated() throws Exception {
        var prompts = new CopyOnWriteArrayList<Prompt>();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new AssertionError("Chat must stream"); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                prompts.add(prompt);
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("收到")))));
            }
        };
        var promptResource = new ClassPathResource("prompts/chat-system.txt");
        var service = new ChatConfiguration().chatService(new SkillModelClient(model, "configured-model"), promptResource);
        assertEquals(List.of("收到"), service.flux("我的预算是两千元", 100L).collectList().block(Duration.ofSeconds(5)));
        service.flux("刚才的预算是多少", 100L).collectList().block(Duration.ofSeconds(5));
        service.flux("新的会话", 200L).collectList().block(Duration.ofSeconds(5));
        assertEquals(3, prompts.size());
        assertEquals(promptResource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8), prompts.get(0).getInstructions().get(0).getText());
        assertTrue(prompts.get(1).getInstructions().stream().anyMatch(message -> "我的预算是两千元".equals(message.getText())));
        assertTrue(prompts.get(1).getInstructions().stream().anyMatch(message -> "收到".equals(message.getText())));
        assertFalse(prompts.get(2).getInstructions().stream().anyMatch(message -> "我的预算是两千元".equals(message.getText())));
    }
}
