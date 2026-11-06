package com.jd.diviner.ai.rec.odin.demo.app.application.skill;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

public final class SkillModelContext {

    private SkillModelContext() {
    }

    public static Counts measure(List<Message> history, List<ToolCallback> callbacks) {
        long textChars = 0;
        long toolResponseChars = 0;
        long toolArgumentChars = 0;
        long toolSchemaChars = 0;
        for (Message message : history) {
            textChars += length(message.getText());
            if (message instanceof ToolResponseMessage toolMessage) {
                for (var response : toolMessage.getResponses()) toolResponseChars += length(response.responseData());
            }
            if (message instanceof AssistantMessage assistant) {
                for (var call : assistant.getToolCalls()) toolArgumentChars += length(call.arguments());
            }
        }
        for (ToolCallback callback : callbacks) {
            var definition = callback.getToolDefinition();
            toolSchemaChars += length(definition.name()) + length(definition.description()) + length(definition.inputSchema());
        }
        return new Counts(textChars, toolResponseChars, toolArgumentChars, toolSchemaChars);
    }

    private static int length(String value) { return value == null ? 0 : value.length(); }

    public record Counts(long textChars, long toolResponseChars, long toolArgumentChars, long toolSchemaChars) {
        public long messageChars() { return textChars + toolResponseChars + toolArgumentChars; }
        public long payloadChars() { return messageChars() + toolSchemaChars; }
    }
}
