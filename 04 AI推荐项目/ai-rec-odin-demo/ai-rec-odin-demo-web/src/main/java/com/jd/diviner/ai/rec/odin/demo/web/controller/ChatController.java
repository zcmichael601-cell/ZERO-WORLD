
package com.jd.diviner.ai.rec.odin.demo.web.controller;

import com.jd.diviner.ai.rec.odin.demo.app.application.chat.ChatService;
import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Controller
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public Flux<ServerSentEvent<Map<String, String>>> stream(@RequestParam(name = "msg") String msg,
                                               @RequestParam(name = "chatId") Long chatId) {
        if (msg.isBlank() || msg.length() > 12000 || chatId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息须为 1–12000 个字符，会话 ID 须为正数");
        }
        return Flux.defer(() -> chatService.flux(msg, chatId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(content -> ServerSentEvent.builder(Map.of("content", content)).event("delta").build())
                .concatWithValues(ServerSentEvent.builder(Map.of("content", "[DONE]")).event("done").build())
                .onErrorResume(error -> {
                    ExecutionLog.current().failure("CHAT_STREAM_FAILED", error, "chatId", chatId);
                    return Flux.just(ServerSentEvent.builder(Map.of("content", "对话请求失败", "details", errorDetails(error))).event("error").build());
                });
    }

    private String errorDetails(Throwable error) {
        StringBuilder details = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (depth > 0) {
                details.append("\nCaused by: ");
            }
            details.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                details.append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        String sanitized = details.toString()
                .replaceAll("(?i)(Bearer\\s+)[^\\s\\\"',;<>]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:api[-_]?key|access[-_]?token|password|secret)\\\"?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&}]+)", "$1[REDACTED]");
        return sanitized.length() > 8000 ? sanitized.substring(0, 8000) + "\n（详情过长，完整异常请查看服务端日志）" : sanitized;
    }

}
