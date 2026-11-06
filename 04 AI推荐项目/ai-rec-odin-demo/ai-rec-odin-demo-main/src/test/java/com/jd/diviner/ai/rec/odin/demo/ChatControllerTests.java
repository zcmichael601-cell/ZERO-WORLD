package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.application.chat.ChatService;
import com.jd.diviner.ai.rec.odin.demo.web.controller.ChatController;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTests {

    private ChatController controller;
    private ChatService agent;

    @BeforeEach
    void setUp() {
        agent = mock(ChatService.class);
        controller = new ChatController(agent);
    }

    @Test
    void streamsContentAndExplicitCompletion() {
        when(agent.flux("你好", 123L)).thenReturn(Flux.just("你好", "\n  世界"));
        List<ServerSentEvent<Map<String, String>>> events = controller.stream("你好", 123L).collectList().block(Duration.ofSeconds(5));
        assertEquals(List.of("delta", "delta", "done"), events.stream().map(ServerSentEvent::event).toList());
        assertEquals("\n  世界", events.get(1).data().get("content"));
        assertEquals("[DONE]", events.get(2).data().get("content"));
        verify(agent).flux("你好", 123L);
    }

    @Test
    void turnsAsyncFailuresIntoSafeErrorEvents() {
        when(agent.flux("你好", 123L)).thenReturn(Flux.concat(Flux.just("部分内容"), Flux.error(new IllegalStateException("secret upstream details"))));
        List<ServerSentEvent<Map<String, String>>> events = controller.stream("你好", 123L).collectList().block(Duration.ofSeconds(5));
        assertEquals(List.of("delta", "error"), events.stream().map(ServerSentEvent::event).toList());
        assertEquals("对话请求失败", events.get(1).data().get("content"));
        assertEquals("IllegalStateException: secret upstream details", events.get(1).data().get("details"));
    }

    @Test
    void handlesFailuresBeforeStreamCreation() {
        when(agent.flux("你好", 123L)).thenThrow(new IllegalStateException("secret upstream details"));
        ServerSentEvent<Map<String, String>> event = controller.stream("你好", 123L).blockFirst(Duration.ofSeconds(5));
        assertEquals("error", event.event());
        assertEquals("对话请求失败", event.data().get("content"));
        assertEquals("IllegalStateException: secret upstream details", event.data().get("details"));
    }

    @Test
    void includesRootCauseAndRedactsCredentials() {
        when(agent.flux("你好", 123L)).thenThrow(new IllegalStateException("Request failed", new IllegalArgumentException("401 Unauthorized; Authorization: Bearer test-token; api-key=private-key; {\"password\":\"private-password\"}")));
        String details = controller.stream("你好", 123L).blockFirst(Duration.ofSeconds(5)).data().get("details");
        assertTrue(details.contains("Caused by: IllegalArgumentException: 401 Unauthorized"));
        assertTrue(details.contains("Bearer [REDACTED]"));
        assertTrue(details.contains("api-key=[REDACTED]"));
        assertTrue(!details.contains("test-token") && !details.contains("private-key") && !details.contains("private-password"));
    }

    @Test
    void handlesEmptyAndExcessivelyLongExceptionMessages() {
        when(agent.flux("你好", 123L)).thenThrow(new IllegalStateException());
        assertEquals("IllegalStateException", controller.stream("你好", 123L).blockFirst(Duration.ofSeconds(5)).data().get("details"));
        doThrow(new IllegalStateException("x".repeat(9000))).when(agent).flux("你好", 123L);
        String details = controller.stream("你好", 123L).blockFirst(Duration.ofSeconds(5)).data().get("details");
        assertTrue(details.length() < 8100);
        assertTrue(details.contains("完整异常请查看服务端日志"));
    }

    @Test
    void rejectsInvalidMessagesAndConversationIds() {
        assertThrows(ResponseStatusException.class, () -> controller.stream("  ", 123L));
        assertThrows(ResponseStatusException.class, () -> controller.stream("a".repeat(12001), 123L));
        assertThrows(ResponseStatusException.class, () -> controller.stream("你好", 0L));
        verifyNoInteractions(agent);
    }

    @Test
    void serializesJsonContentAsServerSentEventsOverHttp() throws Exception {
        when(agent.flux("你好", 123L)).thenReturn(Flux.just("  你好\n世界"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MvcResult result = mvc.perform(post("/chat/stream").param("msg", "你好").param("chatId", "123"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        String body = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("event:delta"));
        assertTrue(body.contains("\"content\":\"  你好\\n世界\""));
        assertTrue(body.contains("event:done"));
    }
}
