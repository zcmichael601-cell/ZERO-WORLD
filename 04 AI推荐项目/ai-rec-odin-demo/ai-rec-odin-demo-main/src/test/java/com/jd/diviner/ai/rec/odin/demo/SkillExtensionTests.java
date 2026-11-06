package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillBehavior;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.AnswerFailure;
import com.jd.diviner.ai.rec.odin.demo.app.bootstrap.SkillConfiguration;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillContext;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillExtension;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillExtensions;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.execution.SkillExecutor;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.execution.WorkflowExecutor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;

class SkillExtensionTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<String> toolExecutions = new CopyOnWriteArrayList<>();
    private final AtomicInteger localActions = new AtomicInteger();
    private final AtomicInteger behaviorsCreated = new AtomicInteger();

    private ToolRegistry tools() {
        return new ToolRegistry(List.of(tool("lookup_parcel"), tool("lookup_delivery")));
    }

    private SkillTool tool(String name) {
        return new SkillTool() {
            public String name() { return name; }
            public String description() { return "隔离测试包裹数据"; }
            public String inputSchema() { return "{\"type\":\"object\",\"properties\":{\"parcelId\":{\"type\":\"string\"}},\"required\":[\"parcelId\"]}"; }
            public JsonNode execute(JsonNode arguments) {
                String parcelId = arguments.path("parcelId").asText();
                assertFalse(parcelId.isBlank());
                toolExecutions.add(name + ":" + parcelId);
                return MAPPER.createObjectNode().put("parcelId", parcelId).put("status", name + "-completed");
            }
        };
    }

    private SkillCatalog catalog(String mode, String handler, ToolRegistry tools, SkillExtensions extensions) throws Exception {
        var root = MAPPER.createObjectNode().put("version", 2);
        var skill = root.putArray("skills").addObject().put("id", "parcel-status").put("name", "包裹状态")
                .put("description", "不修改 Runtime 的扩展测试").put("enabled", true).put("instructions", "parcel-status/SKILL.md")
                .put("executionMode", mode).put("maxModelCalls", 5).put("maxToolCalls", 3);
        if (handler != null) skill.put("handler", handler);
        skill.putArray("requiredInputs").add("parcelId");
        skill.putArray("tools").add("lookup_parcel").add("lookup_delivery");
        skill.putArray("requiredTools").add("lookup_parcel").add("lookup_delivery");
        skill.putArray("requiredOutputs").add("status");
        Resource resource = new ByteArrayResource(MAPPER.writeValueAsBytes(root)) {
            public Resource createRelative(String path) {
                return new ByteArrayResource("---\nname: parcel-status\ndescription: 包裹状态测试\n---\n调用工具后输出 status。".getBytes(StandardCharsets.UTF_8));
            }
        };
        return new SkillCatalog(MAPPER, resource, tools, extensions);
    }

    private SkillExtension extension(boolean unauthorizedTool) {
        return new SkillExtension() {
            public String name() { return "parcel-demo"; }
            public SkillBehavior create(SkillContext context) {
                behaviorsCreated.incrementAndGet();
                return new SkillBehavior(context) {
                    public SkillExecutor executor() {
                        return new WorkflowExecutor(List.of(
                                new WorkflowExecutor.Tool("Skill", run -> MAPPER.createObjectNode().put("command", run.skill().id())),
                                new WorkflowExecutor.Tool(unauthorizedTool ? "unauthorized" : "lookup_parcel", run -> run.input().deepCopy()),
                                new WorkflowExecutor.Local(run -> localActions.incrementAndGet()),
                                new WorkflowExecutor.Decision("lookup_delivery"),
                                new WorkflowExecutor.Local(run -> localActions.incrementAndGet()),
                                new WorkflowExecutor.Answer(true, true)));
                    }
                };
            }
        };
    }

    private ChatResponse completed(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content),
                ChatGenerationMetadata.builder().finishReason("stop").build())));
    }

    private ChatModel workflowModel(boolean invalidFirstAnswer) {
        var invalidPending = new AtomicBoolean(invalidFirstAnswer);
        return new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                try {
                    JsonNode facts = MAPPER.readTree(prompt.getInstructions().get(1).getText());
                    String parcelId = facts.at("/input/parcelId").asText();
                    assertEquals(parcelId, facts.at("/completedResults/lookup_parcel/parcelId").asText());
                    if (facts.has("availableTools")) {
                        assertEquals(1, facts.path("availableTools").size());
                        assertEquals("lookup_delivery", facts.at("/availableTools/0/name").asText());
                        var decision = MAPPER.createObjectNode().put("name", "lookup_delivery");
                        decision.putObject("arguments").put("parcelId", parcelId);
                        return completed(decision.toString());
                    }
                    assertEquals("none", ((OpenAiChatOptions) prompt.getOptions()).getToolChoice());
                    assertEquals(parcelId, facts.at("/completedResults/lookup_delivery/parcelId").asText());
                    if (invalidPending.getAndSet(false)) return completed("invalid-json");
                    return completed(MAPPER.createObjectNode().put("status", parcelId + ":配送中").toString());
                } catch (java.io.IOException exception) {
                    throw new AssertionError(exception);
                }
            }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
        };
    }

    private SkillEngine workflowEngine(ChatModel model, boolean unauthorizedTool) throws Exception {
        var tools = tools();
        var extensions = new SkillExtensions(List.of(extension(unauthorizedTool)));
        return new SkillEngine(MAPPER, catalog("WORKFLOW", "parcel-demo", tools, extensions), tools, new SkillModelClient(model, "test"));
    }

    @Test
    void registeredWorkflowRunsToolsLocalLogicAndModelDecisionWithoutShoppingCode() throws Exception {
        var events = new ArrayList<SkillEngine.Progress>();
        var run = workflowEngine(workflowModel(false), false).executeStreaming("parcel-status",
                MAPPER.createObjectNode().put("parcelId", "P1"), events::add, delta -> {}, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(2, run.modelCalls());
        assertEquals(3, run.traces().size());
        assertEquals(List.of("lookup_parcel:P1", "lookup_delivery:P1"), toolExecutions);
        assertEquals(2, localActions.get());
        assertEquals("P1:配送中", run.output().path("status").asText());
        assertFalse(run.output().has("summary"));
        assertFalse(run.output().has("profile"));
        assertEquals("RUN_COMPLETED", events.get(events.size() - 1).type());
    }

    @Test
    void answerRepairDoesNotReplayWorkflowToolOrLocalNodes() throws Exception {
        var run = workflowEngine(workflowModel(true), false).executeStreaming("parcel-status",
                MAPPER.createObjectNode().put("parcelId", "P2"), event -> {}, delta -> {}, () -> false);
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(3, run.modelCalls());
        assertEquals(3, run.traces().size());
        assertEquals(2, localActions.get());
        assertEquals(2, toolExecutions.size());
    }

    @Test
    void concurrentRunsHaveIndependentBehaviorAndWorkflowCursors() throws Exception {
        var engine = workflowEngine(workflowModel(false), false);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> engine.execute("parcel-status", MAPPER.createObjectNode().put("parcelId", "A")));
            var second = pool.submit(() -> engine.execute("parcel-status", MAPPER.createObjectNode().put("parcelId", "B")));
            var firstRun = first.get(10, TimeUnit.SECONDS);
            var secondRun = second.get(10, TimeUnit.SECONDS);
            assertEquals("SUCCESS", firstRun.status(), firstRun.error());
            assertEquals("SUCCESS", secondRun.status(), secondRun.error());
            assertEquals("A:配送中", firstRun.output().path("status").asText());
            assertEquals("B:配送中", secondRun.output().path("status").asText());
            assertNotEquals(firstRun.runId(), secondRun.runId());
            assertEquals(2, behaviorsCreated.get());
            assertEquals(4, localActions.get());
            assertEquals(3, firstRun.traces().size());
            assertEquals(3, secondRun.traces().size());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void workflowStillEnforcesToolAllowlistAndCancellation() throws Exception {
        var modelCalls = new AtomicInteger();
        ChatModel model = prompt -> { modelCalls.incrementAndGet(); return completed("{}"); };
        var failed = workflowEngine(model, true).execute("parcel-status", MAPPER.createObjectNode().put("parcelId", "P3"));
        assertEquals("FAILED", failed.status());
        assertEquals(0, modelCalls.get());
        assertTrue(toolExecutions.isEmpty());
        assertEquals(0, localActions.get());

        var cancelled = new AtomicBoolean();
        var engine = workflowEngine(model, false);
        assertThrows(CancellationException.class, () -> engine.execute("parcel-status", MAPPER.createObjectNode().put("parcelId", "P3"), event -> {
            if ("TOOL_COMPLETED".equals(event.type()) && "lookup_parcel".equals(event.tool())) cancelled.set(true);
        }, cancelled::get));
        assertEquals(0, localActions.get());
        assertEquals(0, modelCalls.get());
        assertEquals(List.of("lookup_parcel:P3"), toolExecutions);
    }

    @Test
    void declaredLocalCompletionReturnsValidatedToolOutputWithoutCallingTheModel() throws Exception {
        var modelCalls = new AtomicInteger();
        SkillExtension extension = new SkillExtension() {
            public String name() { return "local-parcel"; }
            public boolean completesLocally(
                    com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillDefinition skill) {
                return true;
            }
            public SkillBehavior create(SkillContext context) {
                return new SkillBehavior(context) {
                    public SkillExecutor executor() {
                        return new WorkflowExecutor(List.of(
                                new WorkflowExecutor.Tool(
                                        "Skill",
                                        run -> MAPPER.createObjectNode().put("command", run.skill().id())),
                                new WorkflowExecutor.Tool("lookup_parcel", run -> run.input()),
                                new WorkflowExecutor.Tool("lookup_delivery", run -> run.input()),
                                new WorkflowExecutor.Complete(run -> MAPPER.createObjectNode())));
                    }
                    public JsonNode finish(com.fasterxml.jackson.databind.node.ObjectNode ignored) {
                        return MAPPER.createObjectNode().put(
                                "status",
                                toolResults.get("lookup_delivery").path("status").asText());
                    }
                };
            }
        };
        var tools = tools();
        var extensions = new SkillExtensions(List.of(extension));
        var engine = new SkillEngine(
                MAPPER,
                catalog("WORKFLOW", extension.name(), tools, extensions),
                tools,
                new SkillModelClient(prompt -> {
                    modelCalls.incrementAndGet();
                    throw new AssertionError("本地完成工作流不应调用模型");
                }, "test"));

        var run = engine.execute(
                "parcel-status",
                MAPPER.createObjectNode().put("parcelId", "LOCAL"));

        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(0, run.modelCalls());
        assertEquals(0, modelCalls.get());
        assertEquals(3, run.traces().size());
        assertEquals("lookup_delivery-completed", run.output().path("status").asText());
    }

    @Test
    void genericAgentNeedsNoRegisteredBusinessHandlerOrSummaryField() throws Exception {
        var tools = tools();
        var catalog = catalog("AGENT", null, tools, new SkillExtensions(List.of()));
        ChatModel model = prompt -> {
            long completed = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();
            return switch ((int) completed) {
                case 0 -> SkillEngineTests.tool("load", "Skill", "{\"command\":\"parcel-status\"}");
                case 1 -> SkillEngineTests.tool("parcel", "lookup_parcel", "{\"parcelId\":\"P4\"}");
                case 2 -> SkillEngineTests.tool("delivery", "lookup_delivery", "{\"parcelId\":\"P4\"}");
                default -> completed("{\"status\":\"配送中\"}");
            };
        };
        var run = new SkillEngine(MAPPER, catalog, tools, new SkillModelClient(model, "test"))
                .execute("parcel-status", MAPPER.createObjectNode().put("parcelId", "P4"));
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(4, run.modelCalls());
        assertEquals("配送中", run.output().path("status").asText());
    }

    @Test
    void legacyConfigurationsWithoutHandlerStillResolveTheBuiltinBusinessPolicy() throws Exception {
        Resource resource = new ClassPathResource("skills/recommendation.json") {
            public java.io.InputStream getInputStream() throws java.io.IOException {
                try (var input = super.getInputStream()) {
                    JsonNode configuration = MAPPER.readTree(input);
                    configuration.path("skills").forEach(skill -> ((com.fasterxml.jackson.databind.node.ObjectNode) skill).remove("handler"));
                    return new java.io.ByteArrayInputStream(MAPPER.writeValueAsBytes(configuration));
                }
            }
        };
        var catalog = new SkillCatalog(MAPPER, resource, SkillEngineTests.registry());
        assertEquals(7, catalog.enabledSkills().size());
        catalog.enabledSkills().stream()
                .filter(skill -> !skill.id().equals("shopping-recommendation-jev-cid"))
                .forEach(skill -> assertEquals("commerce", catalog.extension(skill).name()));
        assertEquals("jev-cid",
                catalog.extension(catalog.findEnabled("shopping-recommendation-jev-cid")).name());
    }

    @Test
    void springConfigurationDiscoversBusinessBeansAlongsideBuiltinExtensions() throws Exception {
        var beanFactory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var extension = extension(false);
        beanFactory.registerSingleton("parcelSkillExtension", extension);
        var extensions = new SkillConfiguration().skillExtensions(beanFactory.getBeanProvider(SkillExtension.class));
        var catalog = catalog("WORKFLOW", "parcel-demo", tools(), extensions);
        assertSame(extension, catalog.extension(catalog.findEnabled("parcel-status")));
    }

    @Test
    void runtimeEnforcesRepairLimitEvenWhenExtensionIgnoresRetryBudget() throws Exception {
        SkillExtension extension = new SkillExtension() {
            public String name() { return "unbounded-repair"; }
            public SkillBehavior create(SkillContext context) {
                return new SkillBehavior(context) {
                    public SkillExecutor executor() {
                        return new WorkflowExecutor(List.of(
                                new WorkflowExecutor.Tool("Skill", run -> MAPPER.createObjectNode().put("command", run.skill().id())),
                                new WorkflowExecutor.Tool("lookup_parcel", run -> run.input()),
                                new WorkflowExecutor.Tool("lookup_delivery", run -> run.input()),
                                new WorkflowExecutor.Answer(true, true)));
                    }
                    public List<Message> repairAnswer(String answer, AnswerFailure failure, int attempts, boolean canRetry) {
                        return finalAnswerHistory(failure);
                    }
                };
            }
        };
        var tools = tools();
        var catalog = catalog("WORKFLOW", extension.name(), tools, new SkillExtensions(List.of(extension)));
        var engine = new SkillEngine(MAPPER, catalog, tools, new SkillModelClient(prompt -> completed("invalid-json"), "test"));
        var run = engine.execute("parcel-status", MAPPER.createObjectNode().put("parcelId", "P5"));
        assertEquals("FAILED", run.status());
        assertEquals(3, run.modelCalls());
        assertEquals(3, run.traces().size());
        assertEquals(2, toolExecutions.size());
        assertTrue(run.error().contains("修复次数或执行预算已耗尽"));
    }

    @Test
    void rejectsUnknownOrAmbiguousHandlersAndInvalidWorkflowsBeforeExecution() {
        var extension = extension(false);
        assertThrows(IllegalArgumentException.class, () -> new SkillExtensions(List.of(extension, extension)));
        assertThrows(IllegalArgumentException.class, () -> catalog("WORKFLOW", "missing", tools(), new SkillExtensions(List.of())));
        assertThrows(IllegalArgumentException.class, () -> catalog("WORKFLOW", null, tools(), new SkillExtensions(List.of())));
        assertThrows(IllegalArgumentException.class, () -> catalog("UNKNOWN", "parcel-demo", tools(), new SkillExtensions(List.of(extension))));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowExecutor(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowExecutor(List.of(new WorkflowExecutor.Decision("lookup_delivery"))));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowExecutor(List.of(new WorkflowExecutor.Answer(false, true), new WorkflowExecutor.Answer(false, true))));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowExecutor(List.of(
                new WorkflowExecutor.Complete(run -> MAPPER.createObjectNode()),
                new WorkflowExecutor.Answer(false, true))));
    }
}
