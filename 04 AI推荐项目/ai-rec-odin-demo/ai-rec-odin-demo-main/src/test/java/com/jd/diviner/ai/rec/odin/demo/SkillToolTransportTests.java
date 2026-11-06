package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.bootstrap.SkillModelConfiguration;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillRetryPolicy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;

class SkillToolTransportTests {

    private SkillEngine engine(ChatModel model, boolean streamTools) {
        var registry = SkillEngineTests.registry();
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, SkillEngineTests.agentCatalogResource(), registry);
        return new SkillEngine(SkillEngineTests.MAPPER, catalog, registry,
                new SkillModelClient(model, "test", Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofMillis(10),
                        SkillRetryPolicy.disabled(), Duration.ofSeconds(5), streamTools));
    }

    private ChatResponse completed(String text) {
        return new ChatResponse(List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason("stop").build())));
    }

    private boolean answering(Prompt prompt) {
        return "none".equals(((OpenAiChatOptions) prompt.getOptions()).getToolChoice())
                || "FINAL_ANSWER".equals(StandardSkillTests.facts(prompt).path("phase").asText())
                || prompt.getInstructions().stream()
                .filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
                .map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .anyMatch(response -> "retrieve_product_knowledge".equals(response.name()));
    }

    private ChatResponse decision(Prompt prompt) {
        try {
            var context = SkillEngineTests.MAPPER.readTree(prompt.getInstructions().get(1).getText());
            String name = context.path("availableTools").get(0).path("name").asText();
            String arguments = switch (name) {
                case "Skill" -> "{\"command\":\"shopping-recommendation\"}";
                case "get_user_profile", "get_user_behaviors" -> "{\"username\":\"xiaoming\"}";
                case "search_products" -> "{\"interests\":[\"跑步\"],\"budget\":800,\"limit\":8}";
                case "retrieve_product_knowledge" -> "{\"productIds\":[\"SKU-001\"],\"question\":\"适用场景\"}";
                default -> throw new AssertionError("Unexpected tool " + name);
            };
            var result = SkillEngineTests.MAPPER.createObjectNode().put("name", name);
            result.set("arguments", SkillEngineTests.MAPPER.readTree(arguments));
            return completed(result.toString());
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void joyAiDefaultsToNativeNonStreamingToolsWithoutChangingOtherModelsAndAllowsOverride() {
        var environment = new MockEnvironment()
                .withProperty("dong.spring.ai.joyai.chat.api-key", "test-only-key")
                .withProperty("dong.spring.ai.joyai.chat.base-url", "http://127.0.0.1")
                .withProperty("dong.spring.ai.joyai.chat.completions-path", "/v1/chat/completions")
                .withProperty("dong.spring.ai.joyai.chat.options.model", "JoyAI-750B-joybuilder");
        var config = new SkillModelConfiguration();
        assertFalse(config.skillModelClient(environment).streamToolCalls());
        environment.withProperty("dong.spring.ai.joyai.chat.options.model", "kimi-k3-local-joybuilder");
        assertTrue(config.skillModelClient(environment).streamToolCalls());
        environment.withProperty("odin.skills.model.stream-tool-calls", "false");
        assertFalse(config.skillModelClient(environment).streamToolCalls());
        environment.withProperty("dong.spring.ai.joyai.chat.options.model", "JoyAI-750B-joybuilder")
                .withProperty("odin.skills.model.stream-tool-calls", "true");
        assertTrue(config.skillModelClient(environment).streamToolCalls());
    }

    @Test
    void compatibilityModeLetsModelFinishWithoutForcingAnExtraStreamingCall() {
        var calls = new AtomicInteger();
        var streams = new AtomicInteger();
        var events = new CopyOnWriteArrayList<SkillEngine.Progress>();
        var deltas = new CopyOnWriteArrayList<SkillEngine.ContentDelta>();
        var order = new CopyOnWriteArrayList<String>();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                assertFalse(((OpenAiChatOptions) prompt.getOptions()).getToolCallbacks().isEmpty());
                try { Thread.sleep(50); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                return SkillEngineTests.scriptedResponse(prompt);
            }
            public Flux<ChatResponse> stream(Prompt prompt) {
                streams.incrementAndGet();
                assertFalse(((OpenAiChatOptions) prompt.getOptions()).getToolCallbacks().isEmpty());
                assertEquals("none", ((OpenAiChatOptions) prompt.getOptions()).getToolChoice());
                return Flux.just(completed(SkillEngineTests.FINAL));
            }
        };
        var run = engine(model, false).executeStreaming("shopping-recommendation", SkillProgressTests.input(),
                event -> { events.add(event); order.add(event.type() + ":" + event.modelRound()); },
                delta -> { deltas.add(delta); order.add("CONTENT:" + delta.modelRound()); }, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(6, calls.get());
        assertEquals(0, streams.get());
        assertEquals(5, run.traces().size());
        assertEquals(6, deltas.get(0).modelRound());
        assertTrue(order.indexOf("CONTENT:6") < order.indexOf("MODEL_COMPLETED:6"));
        assertTrue(order.indexOf("CONTENT:6") > order.indexOf("MODEL_STARTED:6"));
        assertTrue(events.stream().anyMatch(event -> "MODEL_WAITING".equals(event.type())));
    }

    @Test
    void cancellationInterruptsBlockingToolDecisionAndDoesNotExecuteTools() throws Exception {
        var cancelled = new AtomicBoolean();
        var interrupted = new CountDownLatch(1);
        ChatModel model = prompt -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException exception) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return SkillEngineTests.scriptedResponse(prompt);
        };
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(java.util.concurrent.CancellationException.class,
                () -> engine(model, false).executeStreaming("shopping-recommendation", SkillProgressTests.input(), event -> {
                    if ("MODEL_WAITING".equals(event.type())) cancelled.set(true);
                    assertNotEquals("TOOL_STARTED", event.type());
                }, delta -> fail("No final answer"), cancelled::get)));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }

    @Test
    void textToolMarkupRecoversUsingExplicitJsonDecisionWithoutExecutingMarkup() {
        var calls = new AtomicInteger();
        var streams = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                assertTrue(((OpenAiChatOptions) prompt.getOptions()).getToolCallbacks().isEmpty());
                assertTrue(prompt.getInstructions().stream().noneMatch(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance));
                assertFalse(prompt.getInstructions().stream().anyMatch(message -> "assistant".equalsIgnoreCase(message.getMessageType().name())
                        && message.getText() != null && message.getText().contains("<tool_call>")));
                return decision(prompt);
            }
            public Flux<ChatResponse> stream(Prompt prompt) {
                if (streams.incrementAndGet() == 1) return Flux.just(completed("<tool_call><function=execute_shell>untrusted</function></tool_call>"));
                assertEquals("none", ((OpenAiChatOptions) prompt.getOptions()).getToolChoice());
                return Flux.just(completed(SkillEngineTests.FINAL));
            }
        };
        var run = engine(model, true).executeStreaming("shopping-recommendation", SkillProgressTests.input(),
                event -> {}, delta -> {}, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(5, calls.get());
        assertEquals(2, streams.get());
        assertEquals(7, run.modelCalls());
        assertEquals(5, run.traces().size());
        assertFalse(run.traces().stream().anyMatch(trace -> "execute_shell".equals(trace.tool())));
    }

    @Test
    void fourthRoundTextToolCallRecoversWithoutRepeatingCompletedTools() {
        var calls = new AtomicInteger();
        var events = new CopyOnWriteArrayList<SkillEngine.Progress>();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                if (calls.incrementAndGet() == 4) return completed("<tool_call><function=search_products>invalid arguments</function></tool_call>");
                if (((OpenAiChatOptions) prompt.getOptions()).getToolCallbacks().isEmpty()) return decision(prompt);
                return SkillEngineTests.scriptedResponse(prompt);
            }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(completed(SkillEngineTests.FINAL)); }
        };
        com.fasterxml.jackson.databind.node.ObjectNode input = SkillProgressTests.input().deepCopy();
        input.put("userDataMode", "DEMO");
        var run = engine(model, false).executeStreaming("shopping-recommendation", input,
                events::add, delta -> {}, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(7, run.modelCalls());
        assertEquals(5, run.traces().size());
        assertEquals(1, run.traces().stream().filter(trace -> "get_user_profile".equals(trace.tool())).count());
        assertEquals(1, run.traces().stream().filter(trace -> "get_user_behaviors".equals(trace.tool())).count());
        assertEquals(1, run.traces().stream().filter(trace -> "search_products".equals(trace.tool())).count());
        assertTrue(events.stream().anyMatch(event -> "MODEL_RETRYING".equals(event.type())));
        assertFalse(events.stream().anyMatch(event -> "MODEL_COMPLETED".equals(event.type()) && event.modelRound() == 4));
        assertTrue(events.stream().anyMatch(event -> "MODEL_STARTED".equals(event.type()) && event.modelRound() == 5));
    }

    @Test
    void structuredDecisionCannotRepeatAnAlreadyCompletedTool() {
        var calls = new AtomicInteger();
        var run = engine(prompt -> {
            int round = calls.incrementAndGet();
            if (round == 4) return completed("<tool_call><function=search_products>text only</function></tool_call>");
            if (round >= 5) return completed("{\"name\":\"get_user_profile\",\"arguments\":{\"username\":\"xiaoming\"}}");
            return SkillEngineTests.scriptedResponse(prompt);
        }, false).execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("工具协议纠正次数"));
        assertEquals(3, run.traces().size());
        assertEquals(1, run.traces().stream().filter(trace -> "get_user_profile".equals(trace.tool())).count());
    }

    @Test
    void persistentTextToolMarkupStopsAfterBoundedRepairsWithoutRunningTools() {
        var calls = new AtomicInteger();
        var run = engine(prompt -> {
            calls.incrementAndGet();
            return completed("<tool_call><function=Skill>not a native call</function></tool_call>");
        }, false).execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("工具协议纠正次数"));
        assertEquals(3, calls.get());
        assertEquals(3, run.modelCalls());
        assertTrue(run.traces().isEmpty());
    }

    @Test
    void emptyBlockingResponseFailsImmediatelyInsteadOfWaitingUntilTimeout() {
        var run = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> engine(prompt -> null, false)
                .executeStreaming("shopping-recommendation", SkillProgressTests.input(), event -> {}, delta -> {}, () -> false));
        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("空响应"));
        assertTrue(run.traces().isEmpty());
    }

    @Test
    void answerRepairCannotReplayACompletedTool() {
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                return answering(prompt) ? completed("not json") : SkillEngineTests.scriptedResponse(prompt);
            }
            public Flux<ChatResponse> stream(Prompt prompt) {
                var request = SkillEngineTests.tool("unexpected-replay", "get_user_profile", "{\"username\":\"xiaoming\"}");
                return Flux.just(new ChatResponse(List.of(new Generation(request.getResult().getOutput(),
                        ChatGenerationMetadata.builder().finishReason("tool_calls").build()))));
            }
        };
        var run = engine(model, false).executeStreaming("shopping-recommendation", SkillProgressTests.input(),
                event -> {}, delta -> {}, () -> false);
        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("拒绝重复执行"));
        assertEquals(5, run.traces().size());
        assertEquals(1, run.traces().stream().filter(trace -> "get_user_profile".equals(trace.tool())).count());
    }

    @Test
    void sixthRoundXmlIsRegeneratedAsFinalJsonRatherThanExecutedOrSentToDecisionMode() {
        var streams = new AtomicInteger();
        var events = new CopyOnWriteArrayList<SkillEngine.Progress>();
        var deltas = new CopyOnWriteArrayList<SkillEngine.ContentDelta>();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                return answering(prompt) ? completed("<tool_call><function=retrieve_product_knowledge>bad</function></tool_call>")
                        : SkillEngineTests.scriptedResponse(prompt);
            }
            public Flux<ChatResponse> stream(Prompt prompt) {
                streams.incrementAndGet();
                assertEquals("none", ((OpenAiChatOptions) prompt.getOptions()).getToolChoice());
                assertFalse(((OpenAiChatOptions) prompt.getOptions()).getToolCallbacks().isEmpty());
                try {
                    var context = SkillEngineTests.MAPPER.readTree(prompt.getInstructions().get(1).getText());
                    assertEquals("FINAL_ANSWER", context.path("phase").asText());
                    assertEquals("TEXT_TOOL_CALL_IN_FINAL_ANSWER", context.path("previousFormatError").asText());
                    assertEquals(2, context.path("completedResults").size());
                    assertFalse(context.path("completedResults").has("retrieve_product_knowledge"));
                    assertTrue(context.path("candidateProducts").isArray());
                    assertEquals("SKU-001", context.at("/productKnowledge/0/productId").asText());
                    assertFalse(context.has("availableTools"));
                } catch (Exception exception) { throw new AssertionError(exception); }
                return Flux.just(completed(SkillEngineTests.FINAL));
            }
        };
        var run = engine(model, false).executeStreaming("shopping-recommendation", SkillProgressTests.input(),
                events::add, deltas::add, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(7, run.modelCalls());
        assertEquals(5, run.traces().size());
        assertEquals(1, run.traces().stream().filter(trace -> "retrieve_product_knowledge".equals(trace.tool())).count());
        assertEquals(1, events.stream().filter(event -> "MODEL_RETRYING".equals(event.type())).count());
        assertTrue(deltas.stream().anyMatch(delta -> delta.modelRound() == 7));
    }

    @Test
    void invalidFinalFormatsHaveBoundedRetriesAndNeverBecomeToolCalls() {
        for (String invalid : List.of("<tool_call ><function=retrieve_product_knowledge>bad</function></tool_call>",
                "{\"summary\":\"unfinished", "[]", "{\"summary\":\"a\",\"summary\":\"b\"}",
                "{\"name\":\"get_user_profile\",\"arguments\":{\"username\":\"other-user\"}}")) {
            var events = new CopyOnWriteArrayList<SkillEngine.Progress>();
            var run = engine(prompt -> {
                return answering(prompt) ? completed(invalid) : SkillEngineTests.scriptedResponse(prompt);
            }, false).execute("shopping-recommendation", SkillProgressTests.input(), events::add, () -> false);
            assertEquals("FAILED", run.status());
            assertEquals(8, run.modelCalls());
            assertEquals(5, run.traces().size());
            assertEquals(2, events.stream().filter(event -> "MODEL_RETRYING".equals(event.type())).count());
            assertTrue(run.error().contains("格式纠正次数"), run.error());
            assertTrue(run.output().isEmpty());
        }
    }

    @Test
    void actualToolCallAfterFinalFormatCorrectionIsRefused() {
        AtomicInteger answers = new AtomicInteger();
        var run = engine(prompt -> {
            if (!answering(prompt)) return SkillEngineTests.scriptedResponse(prompt);
            if (answers.incrementAndGet() == 1) return completed("<tool_call><function=retrieve_product_knowledge>bad</function></tool_call>");
            return SkillEngineTests.tool("unexpected", "get_user_profile", "{\"username\":\"other-user\"}");
        }, false).execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("FAILED", run.status());
        assertEquals(5, run.traces().size());
        assertTrue(run.error().contains("拒绝重复执行"));
    }

    @Test
    void validJsonContainingMarkupAsDataDoesNotTriggerFormatRepair() {
        String answer = SkillEngineTests.FINAL.replace("模型生成的测试分析", "页面文字包含 <tool_call>，仅为数据");
        var run = engine(prompt -> answering(prompt)
                ? completed(answer) : SkillEngineTests.scriptedResponse(prompt), false)
                .execute("shopping-recommendation", SkillProgressTests.input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(6, run.modelCalls());
    }

    @Test
    void cancellationDuringFormatRecoveryPreventsAnyFurtherModelOrToolCall() {
        var cancelled = new AtomicBoolean();
        var calls = new AtomicInteger();
        var engine = engine(prompt -> {
            calls.incrementAndGet();
            return answering(prompt)
                    ? completed("<tool_call><function=retrieve_product_knowledge>text</function></tool_call>")
                    : SkillEngineTests.scriptedResponse(prompt);
        }, false);
        assertThrows(java.util.concurrent.CancellationException.class, () -> engine.execute("shopping-recommendation",
                SkillProgressTests.input(), event -> {
                    if ("MODEL_RETRYING".equals(event.type())) cancelled.set(true);
                }, cancelled::get));
        assertEquals(6, calls.get());
    }
}
