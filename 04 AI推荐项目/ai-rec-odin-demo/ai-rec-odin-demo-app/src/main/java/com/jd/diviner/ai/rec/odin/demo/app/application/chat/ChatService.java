package com.jd.diviner.ai.rec.odin.demo.app.application.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;

public final class ChatService {
    private final ChatClient client;
    private final String systemPrompt;

    public ChatService(ChatClient client, String systemPrompt) {
        this.client = client;
        this.systemPrompt = systemPrompt;
    }

    public Flux<String> flux(String message, Long chatId) {
        return client.prompt().system(systemPrompt).user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, chatId.toString()))
                .stream().content();
    }
}
