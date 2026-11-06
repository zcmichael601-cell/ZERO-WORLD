package com.jd.diviner.ai.rec.odin.demo.app.application.skill;


import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.ModelGenerationException;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.ModelOutputGuard;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.AnswerFailure;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillBehavior;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillContext;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillExecutionException;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.execution.SkillExecutor;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.execution.SkillExecutor.ModelStep;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillDefinition;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

public class SkillEngine {

    private final ObjectMapper mapper;
    private final SkillCatalog catalog;
    private final ToolRegistry registry;
    private final SkillModelClient model;
    private final SkillTimingHistory timingHistory;
    private final ToolCallingManager toolManager = ToolCallingManager.builder().build();

    public SkillEngine(ObjectMapper mapper, SkillCatalog catalog, ToolRegistry registry, SkillModelClient model) {
        this(mapper, catalog, registry, model, new SkillTimingHistory(() -> true));
    }

    public SkillEngine(ObjectMapper mapper, SkillCatalog catalog, ToolRegistry registry, SkillModelClient model,
                       SkillTimingHistory timingHistory) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.registry = registry;
        this.model = model;
        this.timingHistory = timingHistory;
    }

    public SkillTimingHistory.Estimate estimate(String skillId) {
        return estimate(skillId, "REAL");
    }

    public SkillTimingHistory.Estimate estimate(String skillId, String userDataMode) {
        SkillDefinition skill = catalog.findEnabled(skillId);
        if (skill == null) throw new UnknownSkillException();
        var extension = catalog.extension(skill);
        extension.validateUserDataMode(skill, userDataMode);
        return timingHistory.plan(skill, model.modelName(), catalog.instructions(skillId), true,
                userDataMode, extension.completesLocally(skill)).estimate();
    }

    public String modelName() {
        return model.modelName();
    }

    public Duration runTimeout() {
        return model.runTimeout();
    }

    public Duration clientTimeout() {
        return runTimeout().plusSeconds(90);
    }

    public Run execute(String skillId, JsonNode input) {
        return execute(skillId, input, event -> {}, () -> false);
    }

    public Run execute(String skillId, JsonNode input, Consumer<Progress> progress, BooleanSupplier cancelled) {
        SkillDefinition skill = validateInput(skillId, input);
        return new Execution(skill, input.deepCopy(), progress, null, cancelled).run();
    }

    public Run executeStreaming(String skillId, JsonNode input, Consumer<Progress> progress,
                                Consumer<ContentDelta> content, BooleanSupplier cancelled) {
        SkillDefinition skill = validateInput(skillId, input);
        return new Execution(skill, input.deepCopy(), progress, java.util.Objects.requireNonNull(content), cancelled).run();
    }

    public SkillDefinition validateInput(String skillId, JsonNode input) {
        SkillDefinition skill = catalog.findEnabled(skillId);
        if (skill == null) {
            throw new UnknownSkillException();
        }
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("input 必须为 JSON 对象");
        }
        catalog.extension(skill).validateInput(skill, input);
        for (String required : skill.requiredInputs()) {
            JsonNode value = input.get(required);
            if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
                throw new IllegalArgumentException("缺少必填输入: " + required);
            }
        }
        return skill;
    }

    private final class Execution {
        private final SkillDefinition skill;
        private final JsonNode input;
        private final Consumer<Progress> progress;
        private final Consumer<ContentDelta> content;
        private final BooleanSupplier cancelled;
        private int sequence;
        private int contentSequence;
        private final String runId = UUID.randomUUID().toString();
        private final String startedAt = Instant.now().toString();
        private final long start = System.nanoTime();
        private final List<Trace> traces = new ArrayList<>();
        private final List<ModelTurn> turns = new ArrayList<>();
        private final Map<String, JsonNode> toolResults = new LinkedHashMap<>();
        private final SkillContext context;
        private final SkillBehavior behavior;
        private final SkillExecutor executor;
        private final Set<String> seenCallIds = new HashSet<>();
        private List<AssistantMessage.ToolCall> pendingCalls = new ArrayList<>();
        private boolean loaded;
        private boolean toolFailed;
        private int round;
        private final ExecutionLog log;
        private final SkillTimingHistory.Plan timingPlan;
        private final SkillTimingHistory.Tracker timing;

        private Execution(SkillDefinition skill, JsonNode input, Consumer<Progress> progress,
                          Consumer<ContentDelta> content, BooleanSupplier cancelled) {
            this.skill = skill;
            this.input = input;
            this.progress = progress;
            this.content = content;
            this.cancelled = cancelled;
            String userDataMode = input.path("userDataMode").asText("REAL");
            this.log = ExecutionLog.current().with("runId", runId, "skillId", skill.id(), "channel", "SKILL", "userDataMode", userDataMode);
            this.timingPlan = timingHistory.plan(
                    skill,
                    model.modelName(),
                    catalog.instructions(skill.id()),
                    content != null,
                    userDataMode,
                    catalog.extension(skill).completesLocally(skill));
            this.timing = new SkillTimingHistory.Tracker(timingPlan, start);
            this.context = new SkillContext(mapper, skill, input, catalog.instructions(skill.id()), registry, toolResults, log,
                    this::invokePreparedTool, timing::completed, (type, status, message) -> emit(type, status, message, null, null),
                    () -> round, () -> elapsed(start), this::checkRunBudget);
            this.behavior = catalog.extension(skill).create(context);
            this.executor = behavior.executor();
        }

        private void checkCancelled() {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Skill 执行已取消");
            }
        }

        private void emit(String type, String status, String message, String tool, String toolCallId) {
            checkCancelled();
            if (loaded) timing.completed("loading");
            if (loaded && toolResults.keySet().containsAll(skill.requiredTools())) timing.completed("planning");
            for (String stage : skill.tools().stream().map(SkillTimingHistory::toolStage).distinct().toList()) {
                if (skill.tools().stream().filter(name -> SkillTimingHistory.toolStage(name).equals(stage)).allMatch(toolResults::containsKey)) {
                    timing.completed(stage);
                }
            }
            if (!"MODEL_WAITING".equals(type)) {
                log.info(type, "round", round, "status", status, "elapsedMs", elapsed(start),
                        "tool", tool, "toolCallId", toolCallId, "sequence", sequence + 1);
            }
            progress.accept(new Progress(runId, skill.id(), ++sequence, type, status, message, round,
                    tool, toolCallId, elapsed(start), Instant.now().toString(), timing.snapshot("RUN_STARTED".equals(type))));
        }

        private ChatResponse requestModel(Prompt prompt, long roundStart, boolean streaming) {
            for (int attempt = 1; ; attempt++) {
                checkCancelled();
                long remaining = Math.min(model.roundTimeout().toMillis() - elapsed(roundStart), model.runTimeout().toMillis() - elapsed(start));
                if (remaining <= 0) throw new SkillExecutionException("模型重试预算已耗尽，执行已停止");
                log.info("MODEL_ATTEMPT_STARTED", "round", round, "attempt", attempt, "remainingMs", remaining);
                try {
                    return streaming ? streamResponse(prompt, attempt, remaining) : callResponse(prompt, attempt, remaining);
                } catch (RuntimeException exception) {
                    checkCancelled();
                    long delay = model.retryPolicy().delayMillis(attempt);
                    boolean retryable = exception instanceof IncompleteModelStreamException || model.retryPolicy().transientFailure(exception);
                    boolean retry = retryable && attempt < model.retryPolicy().maxAttempts()
                            && Math.min(model.roundTimeout().toMillis() - elapsed(roundStart), model.runTimeout().toMillis() - elapsed(start)) > delay;
                    log.failure("MODEL_ATTEMPT_FAILED", exception, "round", round, "attempt", attempt, "retry", retry);
                    if (!retry) throw exception;
                    emit("MODEL_RETRYING", "RUNNING", "第 " + round + " 轮模型连接暂时异常，"
                            + delay + " 毫秒后进行第 " + (attempt + 1) + "/" + model.retryPolicy().maxAttempts()
                            + " 次尝试；保留已完成工具结果，重新生成本轮内容", null, null);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("模型重试已取消");
                    }
                }
            }
        }

        private ChatResponse callResponse(Prompt prompt, int attempt, long remaining) {
            long callStart = System.nanoTime();
            ExecutionLog roundLog = log.with("round", round, "attempt", attempt, "channel", "MODEL");
            var response = reactor.core.publisher.Mono.fromCallable(() -> {
                try (var scope = roundLog.activate()) {
                    return model.chatModel().call(prompt);
                }
            }).switchIfEmpty(reactor.core.publisher.Mono.error(new SkillExecutionException("模型返回了空响应")))
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                    .map(value -> new ModelSignal(value, true)).flux();
            var waiting = Flux.interval(model.progressInterval()).map(tick -> new ModelSignal(null, false));
            try {
                return Flux.merge(response, waiting).takeUntil(ModelSignal::finished)
                        .doOnNext(signal -> {
                            checkCancelled();
                            if (!signal.finished()) {
                                roundLog.info("MODEL_WAITING", "transport", "CALL", "elapsedMs", elapsed(callStart),
                                        "remainingRunMs", Math.max(0, model.runTimeout().toMillis() - elapsed(start)));
                                emit("MODEL_WAITING", "RUNNING", "第 " + round + " 轮正在等待模型完整响应，已等待 "
                                        + elapsed(callStart) / 1000 + " 秒"
                                        + (content == null ? "" : "；收到响应后立即展示内容"), null, null);
                            }
                        }).filter(ModelSignal::finished).map(ModelSignal::response).next().block(Duration.ofMillis(remaining));
            } finally {
                roundLog.info("MODEL_CALL_STOPPED", "elapsedMs", elapsed(callStart));
            }
        }

        private ChatResponse streamResponse(Prompt prompt, int attempt, long remaining) {
            AtomicReference<ChatResponse> complete = new AtomicReference<>();
            long callStart = System.nanoTime();
            long[] received = {0, callStart, 0, 0, 0};
            ModelOutputGuard guard = behavior.guardModelOutput() ? new ModelOutputGuard() : null;
            AtomicReference<String> terminalReason = new AtomicReference<>();
            ExecutionLog roundLog = log.with("round", round, "attempt", attempt, "channel", "MODEL");
            var upstream = model.chatModel().stream(prompt).timeout(model.idleTimeout())
                    .doFinally(signal -> roundLog.info("MODEL_UPSTREAM_TERMINATED", "signal", signal.name(), "elapsedMs", elapsed(callStart)))
                    .map(chunk -> new ModelSignal(chunk, false)).concatWithValues(new ModelSignal(null, true));
            var waiting = Flux.interval(model.progressInterval()).map(tick -> new ModelSignal(null, false));
            var responses = Flux.merge(upstream, waiting).takeUntil(ModelSignal::finished).handle((signal, sink) -> {
                checkCancelled();
                if (signal.finished()) return;
                if (signal.response() == null) {
                    roundLog.info("MODEL_WAITING", "elapsedMs", elapsed(callStart), "chunks", received[0],
                            "lastChunkAgoMs", received[0] == 0 ? null : elapsed(received[1]),
                            "contentChars", received[2], "reasoningChars", received[3], "toolArgumentChars", received[4],
                            "remainingRunMs", Math.max(0, model.runTimeout().toMillis() - elapsed(start)));
                    String message = "第 " + round + " 轮已等待 " + elapsed(callStart) / 1000 + " 秒："
                            + (received[0] == 0 ? "尚未收到模型响应分片"
                            : "已收到 " + received[0] + " 个响应分片，距上次响应 " + elapsed(received[1]) / 1000 + " 秒，"
                            + (received[2] > 0 ? "正文仍在生成" : received[4] > 0 ? "正在生成工具调用参数"
                            : received[3] > 0 ? "模型正在推理，尚未生成正文" : "尚未收到可展示正文"))
                            + "；单轮最多等待 " + model.roundTimeout().toSeconds() + " 秒";
                    emit("MODEL_WAITING", "RUNNING", message, null, null);
                    return;
                }
                if (received[0]++ == 0) roundLog.info("MODEL_FIRST_CHUNK", "elapsedMs", elapsed(callStart));
                received[1] = System.nanoTime();
                sink.next(signal.response());
            }).cast(ChatResponse.class).doOnNext(chunk -> {
                checkCancelled();
                if (chunk.getResult() != null) {
                    String finish = chunk.getResult().getMetadata().getFinishReason();
                    if (finish != null && !finish.isBlank()) terminalReason.set(finish);
                    var output = chunk.getResult().getOutput();
                    Object reasoning = output.getMetadata().get("reasoningContent");
                    if (!(reasoning instanceof String)) reasoning = output.getMetadata().get("reasoning_content");
                    if (reasoning instanceof String reasoningText && !reasoningText.isEmpty()) {
                        if (received[3] == 0) roundLog.info("MODEL_FIRST_REASONING", "elapsedMs", elapsed(callStart));
                        received[3] += reasoningText.length();
                        if (guard != null) guard.reasoning(reasoningText);
                    }
                    for (var toolCall : output.getToolCalls()) {
                        if (toolCall.arguments() != null) received[4] += toolCall.arguments().length();
                        if (guard != null) guard.toolArguments(toolCall.arguments());
                    }
                    String text = output.getText();
                    if (text != null && !text.isEmpty()) {
                        if (received[2] == 0) roundLog.info("MODEL_FIRST_CONTENT", "elapsedMs", elapsed(callStart), "chunks", received[0]);
                        received[2] += text.length();
                        if (guard != null) guard.content(text);
                        content.accept(new ContentDelta(runId, skill.id(), ++contentSequence, round, text));
                    }
                }
            });
            long waitMillis = Math.max(1, Math.min(remaining, model.runTimeout().toMillis() - elapsed(start)));
            try {
                new MessageAggregator().aggregate(responses, complete::set).then().block(Duration.ofMillis(waitMillis));
            } finally {
                roundLog.info("MODEL_STREAM_STOPPED", "elapsedMs", elapsed(callStart), "chunks", received[0],
                        "contentChars", received[2], "reasoningChars", received[3], "toolArgumentChars", received[4],
                        "aggregated", complete.get() != null, "waitBudgetMs", waitMillis, "finishReason", terminalReason.get());
            }
            checkCancelled();
            ChatResponse response = complete.get();
            String finishReason = response == null || response.getResult() == null ? null
                    : response.getResult().getMetadata().getFinishReason();
            if (!"stop".equalsIgnoreCase(finishReason) && !"tool_calls".equalsIgnoreCase(finishReason)) {
                if (finishReason == null || finishReason.isBlank()) throw new IncompleteModelStreamException();
                if ("length".equalsIgnoreCase(finishReason)) throw new ModelGenerationException("LENGTH");
                throw new SkillExecutionException("模型流未完整结束，不能确认结果或执行工具（finish_reason="
                        + ("content_filter".equalsIgnoreCase(finishReason) ? "content_filter" : "UNKNOWN") + "）");
            }
            return response;
        }

        private Run run() {
            try (var scope = log.activate()) {
                log.info("RUN_CONFIG", "model", model.modelName(), "streaming", content != null,
                        "maxModelCalls", skill.maxModelCalls(), "maxToolCalls", skill.maxToolCalls(),
                        "idleTimeoutMs", model.idleTimeout().toMillis(), "roundTimeoutMs", model.roundTimeout().toMillis(),
                        "progressIntervalMs", model.progressInterval().toMillis(), "runBudgetMs", model.runTimeout().toMillis(),
                        "maxAttempts", model.retryPolicy().maxAttempts(), "retryDelayMs", model.retryPolicy().initialDelay().toMillis(),
                        "streamToolCalls", model.streamToolCalls());
                return runWithContext();
            }
        }

        private Run runWithContext() {
            ConversationState conversation = new ConversationState(behavior.initialHistory());
            try {
                startExecution();
                for (round = 1; round <= skill.maxModelCalls(); round++) {
                    checkRunBudget();
                    RoundPlan plan = planRound(conversation);
                    if (plan.localOutput() != null) {
                        return completeLocally(plan);
                    }
                    RoundResponse reply;
                    try {
                        reply = requestRound(plan);
                    } catch (ModelGenerationException exception) {
                        recoverGeneration(conversation, plan, exception);
                        continue;
                    }
                    if (repairToolProtocol(conversation, plan, reply)) continue;

                    ChatResponse response = reply.response();
                    if (!plan.streaming() && content != null && plan.toolsReady() && !response.hasToolCalls()) {
                        String text = response.getResult().getOutput().getText();
                        if (text != null && !text.isBlank()) {
                            checkCancelled();
                            content.accept(new ContentDelta(runId, skill.id(), ++contentSequence, round, text));
                        }
                    }
                    emit("MODEL_COMPLETED", "SUCCESS", response.hasToolCalls()
                            ? "第 " + round + " 轮模型已响应，请求调用 " + response.getResult().getOutput().getToolCalls().size() + " 个工具"
                            : "已收到大模型的分析结果", null, null);
                    if (response.hasToolCalls()) {
                        executeRoundTools(conversation, plan, response);
                    } else {
                        Optional<Run> completed = validateFinalAnswer(conversation, plan, response.getResult().getOutput().getText());
                        if (completed.isPresent()) return completed.get();
                    }
                }
                throw new SkillExecutionException("超过 Skill 配置的模型调用轮数上限");
            } catch (CancellationException exception) {
                log.info("RUN_CANCELLED", "round", round, "elapsedMs", elapsed(start));
                throw exception;
            } catch (SkillExecutionException exception) {
                log.failure("RUN_ERROR", exception, "round", round, "elapsedMs", elapsed(start));
                emit("RUN_FAILED", "FAILED", exception.getMessage(), null, null);
                return result("FAILED", mapper.createObjectNode(), exception.getMessage());
            } catch (RuntimeException exception) {
                log.failure("RUN_PROTOCOL_ERROR", exception, "round", round, "elapsedMs", elapsed(start));
                emit("RUN_FAILED", "FAILED", "模型工具协议或结果格式无效，执行已停止", null, null);
                return result("FAILED", mapper.createObjectNode(), "模型工具协议或结果格式无效，请检查调用轨迹和服务端日志");
            }
        }


        private final class ConversationState {
            private List<Message> history;
            private boolean nativeToolTransport = executor.separatesToolAndAnswerPhases() || !model.streamToolCalls();
            private int protocolRepairs;
            private int toolPlanRepairs;
            private int generationRepairs;
            private boolean generationFinalAnswer;
            private final Map<AnswerFailure.Kind, Integer> answerRepairs = new LinkedHashMap<>();
            private boolean finalContextPrepared;

            private ConversationState(List<Message> history) {
                this.history = history;
            }
        }

        private record RoundPlan(boolean toolsReady, boolean decisionMode, boolean finalAnswer, boolean streaming,
                                 List<ToolCallback> callbacks, OpenAiChatOptions options, Prompt prompt,
                                 Prompt modelPrompt, ObjectNode localOutput) {}

        private record RoundResponse(ChatResponse response, boolean invalidDecision) {}

        private void recoverGeneration(ConversationState conversation, RoundPlan plan, ModelGenerationException failure) {
            checkRunBudget();
            boolean canRetry = loaded && conversation.generationRepairs < 2 && round < skill.maxModelCalls();
            List<Message> repaired = canRetry ? behavior.recoverGeneration(failure.reason(), plan.toolsReady()) : null;
            if (repaired == null) {
                throw new SkillExecutionException(failure.getMessage() + "；无法继续恢复或修正预算已耗尽", failure);
            }
            conversation.generationRepairs++;
            conversation.generationFinalAnswer = plan.toolsReady();
            conversation.finalContextPrepared = plan.toolsReady();
            conversation.history = new ArrayList<>(repaired);
            log.info("MODEL_GENERATION_RETRY", "round", round, "reason", failure.reason(),
                    "repairAttempt", conversation.generationRepairs, "maxRepairs", 2,
                    "completedTools", toolResults.keySet(), "nextPhase", plan.toolsReady() ? "ANSWER" : "AGENT",
                    "remainingModelCalls", skill.maxModelCalls() - round);
            emit("MODEL_RETRYING", "RUNNING", "检测到模型重复生成或响应被截断，已停止本轮；保留已完成的工具结果，"
                    + (plan.toolsReady() ? "正在重新生成简洁答案" : "正在根据已有事实重新规划")
                    + "（" + conversation.generationRepairs + "/2）", null, null);
        }

        private void startExecution() {
            emit("RUN_STARTED", "RUNNING", "Skill 执行已开始，准备请求当前配置的大模型", null, null);
            executor.start(context);
        }

        private void checkRunBudget() {
            checkCancelled();
            if (elapsed(start) > model.runTimeout().toMillis() || Thread.currentThread().isInterrupted()) {
                throw new SkillExecutionException("Skill 执行超时或被中断");
            }
        }

        private RoundPlan planRound(ConversationState conversation) {
            ModelStep step = executor.nextStep(context);
            boolean toolsReady = loaded && toolResults.keySet().containsAll(skill.requiredTools());
            if (step.mode() == ModelStep.Mode.ANSWER && !toolsReady) {
                throw new SkillExecutionException("Workflow 未完成 Skill 加载或必需工具，不能进入最终回答节点");
            }
            if (step.mode() == ModelStep.Mode.COMPLETE) {
                if (!toolsReady) {
                    throw new SkillExecutionException("Workflow 未完成 Skill 加载或必需工具，不能在本地完成");
                }
                if (!catalog.extension(skill).completesLocally(skill)) {
                    throw new SkillExecutionException("当前 Skill 未声明本地完成能力");
                }
                return new RoundPlan(
                        true, false, false, false, List.of(), null, null, null, step.localOutput());
            }
            boolean decisionMode = step.mode() == ModelStep.Mode.DECISION || (conversation.protocolRepairs > 0 && !toolsReady);
            List<ToolCallback> callbacks = step.includeToolSchemas() ? callbacks() : List.of();
            if (loaded && step.mode() == ModelStep.Mode.AGENT) {
                List<String> available = behavior.availableTools(callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList(),
                        skill.maxToolCalls() - traces.size(), skill.maxModelCalls() - round + 1);
                callbacks = callbacks.stream().filter(callback -> available.contains(callback.getToolDefinition().name())).toList();
            }
            boolean finalAnswer = step.mode() == ModelStep.Mode.ANSWER || (step.mode() == ModelStep.Mode.AGENT
                    && ((!behavior.allowFurtherTools() && conversation.nativeToolTransport) || callbacks.isEmpty()
                        || !conversation.answerRepairs.isEmpty() || conversation.protocolRepairs > 0
                        || conversation.generationFinalAnswer) && toolsReady);
            if (step.mode() == ModelStep.Mode.DECISION) {
                callbacks = callbacks.stream().filter(callback -> step.tool().equals(callback.getToolDefinition().name())).toList();
                if (callbacks.isEmpty()) throw new SkillExecutionException("Workflow 决策节点请求了未配置或尚未开放的工具");
            }
            if ((step.rebuildContext() || (finalAnswer && behavior.guardModelOutput())) && !conversation.finalContextPrepared) {
                conversation.history = behavior.finalAnswerHistory(new AnswerFailure("NONE"));
                conversation.finalContextPrepared = true;
            }
            if (loaded && step.mode() == ModelStep.Mode.AGENT && !finalAnswer) {
                conversation.history = behavior.prepareAgentHistory(conversation.history,
                        callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList(),
                        skill.maxToolCalls() - traces.size(), skill.maxModelCalls() - round + 1);
            }
            boolean streaming = content != null && (finalAnswer || callbacks.isEmpty() || !conversation.nativeToolTransport);
            OpenAiChatOptions options = OpenAiChatOptions.builder().model(model.modelName())
                    .internalToolExecutionEnabled(false).parallelToolCalls(false).streamUsage(streaming)
                    .toolCallbacks(callbacks).build();
            if (skill.reasoningEffort() != null) options.setReasoningEffort(skill.reasoningEffort());
            if (finalAnswer && !callbacks.isEmpty()) options.setToolChoice("none");
            Prompt prompt = new Prompt(conversation.history, options);
            Prompt modelPrompt = decisionMode ? SkillToolDecision.prompt(mapper, skill, input,
                    loaded ? catalog.instructions(skill.id()) : "首先调用 Skill 加载技能，command 为 " + skill.id(),
                    toolResults, callbacks, model.modelName(), behavior::toolData) : prompt;
            return new RoundPlan(
                    toolsReady, decisionMode, finalAnswer, streaming, callbacks, options, prompt, modelPrompt, null);
        }

        private RoundResponse requestRound(RoundPlan plan) {
            long callStart = System.nanoTime();
            ChatResponse response;
            boolean invalidDecision = false;
            String stage = !loaded ? "加载技能" : toolResults.keySet().containsAll(skill.requiredTools())
                    ? "分析工具结果" : "选择下一步工具";
            timing.enter(!loaded ? "loading" : plan.decisionMode() ? "planning"
                    : toolResults.keySet().containsAll(skill.requiredTools()) ? "synthesis" : "planning");
            var contextSize = SkillModelContext.measure(plan.modelPrompt().getInstructions(), plan.decisionMode() ? List.of() : plan.callbacks());
            log.info("MODEL_REQUEST", "round", round, "channel", "MODEL", "stage", stage,
                    "historyMessages", plan.modelPrompt().getInstructions().size(), "availableTools", plan.callbacks().size(),
                    "messageTextChars", contextSize.messageChars(), "toolResponseChars", contextSize.toolResponseChars(),
                    "toolArgumentChars", contextSize.toolArgumentChars(), "toolSchemaChars", contextSize.toolSchemaChars(),
                    "promptPayloadChars", contextSize.payloadChars(),
                    "reasoningEffort", skill.reasoningEffort(), "transport", plan.streaming() ? "STREAM" : "CALL", "toolChoice", plan.options().getToolChoice(),
                    "toolProtocol", plan.decisionMode() ? "JSON_DECISION" : "NATIVE",
                    "completedTools", toolResults.size(), "remainingRunMs", Math.max(0, model.runTimeout().toMillis() - elapsed(start)));
            emit("MODEL_STARTED", "RUNNING", "正在请求大模型" + stage + "（第 " + round + " 轮，"
                    + (plan.streaming() ? "流式响应" : "完整响应") + "）", null, null);
            try {
                response = requestModel(plan.modelPrompt(), callStart, plan.streaming());
                if (response == null || response.getResult() == null) {
                    throw new SkillExecutionException("模型返回了空响应");
                }
                if (!plan.streaming()) {
                    String finish = response.getResult().getMetadata().getFinishReason();
                    if ("length".equalsIgnoreCase(finish)) throw new ModelGenerationException("LENGTH");
                    if (finish != null && !finish.isBlank() && !"stop".equalsIgnoreCase(finish) && !"tool_calls".equalsIgnoreCase(finish)) {
                        throw new SkillExecutionException("模型未正常结束，不能确认结果或执行工具");
                    }
                    if (behavior.guardModelOutput()) {
                        ModelOutputGuard guard = new ModelOutputGuard();
                        var output = response.getResult().getOutput();
                        guard.content(output.getText());
                        Object reasoning = output.getMetadata().get("reasoningContent");
                        if (!(reasoning instanceof String)) reasoning = output.getMetadata().get("reasoning_content");
                        if (reasoning instanceof String text) guard.reasoning(text);
                        output.getToolCalls().forEach(call -> guard.toolArguments(call.arguments()));
                    }
                }
                if (plan.decisionMode()) {
                    try {
                        response = SkillToolDecision.decode(mapper, response, plan.callbacks(), toolResults);
                    } catch (SkillToolDecision.InvalidDecision exception) {
                        invalidDecision = true;
                    }
                }
            } catch (RuntimeException exception) {
                checkCancelled();
                turns.add(new ModelTurn(round, "FAILED", elapsed(callStart), null, model.modelName(), null, null, List.of()));
                if (exception instanceof ModelGenerationException failure) {
                    log.info("MODEL_OUTPUT_REJECTED", "round", round, "reason", failure.reason(),
                            "elapsedMs", elapsed(callStart), "transport", plan.streaming() ? "STREAM" : "CALL");
                    throw failure;
                }
                String failure = modelFailure(exception);
                log.failure("MODEL_ERROR", exception, "round", round, "channel", "MODEL", "stage", stage,
                        "elapsedMs", elapsed(callStart), "model", model.modelName());
                emit("MODEL_FAILED", "FAILED", "第 " + round + " 轮模型调用失败，已停止执行", null, null);
                throw new SkillExecutionException(failure + "（第 " + round + " 轮，RUN " + runId + "）");
            } finally {
                timing.leave();
            }
            checkCancelled();
            recordModelResponse(response, callStart);
            return new RoundResponse(response, invalidDecision);
        }

        private void recordModelResponse(ChatResponse response, long callStart) {
            AssistantMessage assistant = response.getResult().getOutput();
            var metadata = response.getMetadata();
            var usage = metadata.getUsage();
            log.info("MODEL_RESPONSE", "round", round, "elapsedMs", elapsed(callStart),
                    "responseId", metadata.getId(), "model", metadata.getModel(),
                    "finishReason", response.getResult().getMetadata().getFinishReason(),
                    "promptTokens", usage == null ? null : usage.getPromptTokens(),
                    "completionTokens", usage == null ? null : usage.getCompletionTokens(),
                    "toolCalls", assistant.getToolCalls().size(),
                    "contentChars", assistant.getText() == null ? 0 : assistant.getText().length());
            turns.add(new ModelTurn(round, "SUCCESS", elapsed(callStart), metadata.getId(), metadata.getModel(),
                    usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens(),
                    assistant.getToolCalls()));
        }

        private boolean repairToolProtocol(ConversationState conversation, RoundPlan plan, RoundResponse reply) {
            AssistantMessage assistant = reply.response().getResult().getOutput();
            boolean invalidProtocol = reply.invalidDecision() || (!reply.response().hasToolCalls() && !plan.toolsReady()
                    && assistant.getText() != null && assistant.getText().contains("<tool_call>"));
            if (!invalidProtocol) return false;
            if (conversation.protocolRepairs >= 2 || round >= skill.maxModelCalls()) {
                throw new SkillExecutionException("模型仍未返回合法的工具决策，工具协议纠正次数或模型轮数已达上限；"
                        + "未执行未经校验的工具指令，请检查模型网关响应");
            }
            conversation.protocolRepairs++;
            conversation.nativeToolTransport = true;
            List<String> remainingTools = loaded ? skill.requiredTools().stream()
                    .filter(name -> !toolResults.containsKey(name)).toList() : List.of("Skill");
            log.info("MODEL_PROTOCOL_RETRY", "round", round, "channel", "MODEL",
                    "repairAttempt", conversation.protocolRepairs, "maxRepairs", 2,
                    "previousTransport", plan.streaming() ? "STREAM" : "CALL",
                    "nextTransport", "CALL", "nextProtocol", "JSON_DECISION", "remainingTools", remainingTools,
                    "completedTools", toolResults.keySet(), "reason", reply.invalidDecision() ? "INVALID_DECISION" : "TEXT_TOOL_CALL");
            emit("MODEL_RETRYING", "RUNNING", "模型工具调用格式异常，正在纠正并继续执行（"
                    + conversation.protocolRepairs + "/2），已完成的工具结果保留", null, null);
            return true;
        }

        private void executeRoundTools(ConversationState conversation, RoundPlan plan, ChatResponse response) {
            AssistantMessage assistant = response.getResult().getOutput();
            if (plan.finalAnswer()) throw new SkillExecutionException("模型在最终回答阶段仍请求工具调用，已拒绝重复执行");
            if (traces.size() >= skill.maxToolCalls()) throw new SkillExecutionException("超过 Skill 配置的工具调用次数上限");
            if (loaded) {
                String correction = behavior.reviewToolCalls(assistant.getToolCalls(), skill.maxToolCalls() - traces.size(),
                        skill.maxModelCalls() - round + 1);
                if (correction != null) {
                    if (conversation.toolPlanRepairs >= 2 || round >= skill.maxModelCalls()) {
                        throw new SkillExecutionException("模型持续请求重复或超出执行预算的工具，已停止；未重复执行工具");
                    }
                    conversation.toolPlanRepairs++;
                    conversation.history.add(new SystemMessage(correction));
                    log.info("TOOL_PLAN_RETRY", "round", round, "repairAttempt", conversation.toolPlanRepairs,
                            "requestedTools", assistant.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList(),
                            "remainingToolCalls", skill.maxToolCalls() - traces.size());
                    emit("TOOLS_RETURNED", "SUCCESS", "已拦截重复或不满足执行条件的工具请求，未执行本批工具；保留已完成结果，请模型重新规划", null, null);
                    return;
                }
            }
            if (conversation.protocolRepairs > 0 && assistant.getToolCalls().stream().anyMatch(call ->
                    (loaded && "Skill".equals(call.name())) || toolResults.containsKey(call.name()))) {
                throw new SkillExecutionException("模型在协议纠正后请求重复调用已完成工具，已拒绝重复执行");
            }
            validateCalls(assistant.getToolCalls(), plan.callbacks());
            pendingCalls = new ArrayList<>(assistant.getToolCalls());
            conversation.history = new ArrayList<>(toolManager.executeToolCalls(plan.prompt(), response).conversationHistory());
            if (toolFailed) {
                throw new SkillExecutionException("工具执行失败，已停止后续执行；请检查调用轨迹");
            }
            executor.toolsCompleted(assistant.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList());
            emit("TOOLS_RETURNED", "SUCCESS", "本轮工具结果已写入对话，准备交给大模型继续分析", null, null);
        }

        private Optional<Run> validateFinalAnswer(ConversationState conversation, RoundPlan plan, String answer) {
            if (!plan.toolsReady()) throw new SkillExecutionException("模型提前结束，未完成 Skill 加载或配置要求的工具调用");
            timing.completed("synthesis");
            timing.enter("validation");
            emit("VALIDATION_STARTED", "RUNNING", "正在校验结果格式、必需字段和工具事实引用", null, null);
            long validationStart = System.nanoTime();
            JsonNode output;
            try {
                output = finish(answer);
            } catch (AnswerFailure failure) {
                timing.leave();
                int attempts = conversation.answerRepairs.getOrDefault(failure.kind(), 0);
                boolean canRetry = attempts < 2 && round < skill.maxModelCalls() && elapsed(start) < model.runTimeout().toMillis();
                List<Message> repaired = behavior.repairAnswer(answer, failure, attempts, canRetry);
                if (!canRetry) throw new SkillExecutionException("最终答案修复次数或执行预算已耗尽");
                conversation.history = java.util.Objects.requireNonNull(repaired, "业务修复上下文不能为空");
                conversation.finalContextPrepared = true;
                conversation.answerRepairs.put(failure.kind(), attempts + 1);
                return Optional.empty();
            }
            timing.leave();
            timing.completed("validation");
            log.info("VALIDATION_COMPLETED", "round", round, "elapsedMs", elapsed(validationStart));
            emit("RUN_COMPLETED", "SUCCESS", "结果校验通过，Skill 已执行完成", null, null);
            return Optional.of(result("SUCCESS", output, null));
        }

        private Run completeLocally(RoundPlan plan) {
            timing.completed("planning");
            timing.enter("validation");
            emit("VALIDATION_STARTED", "RUNNING", "正在校验本地组装结果与必需字段", null, null);
            long validationStart = System.nanoTime();
            JsonNode output;
            try {
                output = validateOutput(plan.localOutput().deepCopy());
            } catch (AnswerFailure failure) {
                throw new SkillExecutionException("本地结果校验未通过：" + failure.reason(), failure);
            }
            timing.leave();
            timing.completed("validation");
            log.info("LOCAL_COMPLETION_VALIDATED", "round", round, "elapsedMs", elapsed(validationStart));
            emit("RUN_COMPLETED", "SUCCESS", "工具结果已在本地组装并校验完成", null, null);
            return result("SUCCESS", output, null);
        }

        private void invokePreparedTool(String name, JsonNode arguments) {
            checkRunBudget();
            List<ToolCallback> available = callbacks();
            ToolCallback selected = available.stream().filter(tool -> name.equals(tool.getToolDefinition().name()))
                    .findFirst().orElseThrow(() -> new SkillExecutionException("准备流程请求了未配置的工具"));
            var call = new AssistantMessage.ToolCall("workflow_" + UUID.randomUUID(), "function", name, arguments.toString());
            validateCalls(List.of(call), available);
            pendingCalls = new ArrayList<>(List.of(call));
            selected.call(call.arguments());
            if (toolFailed) throw new SkillExecutionException("准备流程工具执行失败，请检查调用轨迹");
        }

        private List<ToolCallback> callbacks() {
            if (!loaded) {
                return List.of(callback("Skill", "加载技能的完整指令。可用技能：" + skill.id() + " — " + skill.description(),
                        "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"enum\":[\"" + skill.id()
                                + "\"]}},\"required\":[\"command\"],\"additionalProperties\":false}", arguments -> {
                            if (!skill.id().equals(arguments.path("command").asText())) {
                                throw new SkillExecutionException("不能加载当前配置以外的 Skill");
                            }
                            loaded = true;
                            return mapper.valueToTree(Map.of("name", skill.id(), "instructions", catalog.instructions(skill.id()),
                                    "allowedTools", skill.tools(), "requiredTools", skill.requiredTools(), "requiredOutputs", skill.requiredOutputs()));
                        }));
            }
            return skill.tools().stream().map(name -> {
                SkillTool tool = behavior.resolveTool(name);
                return callback(tool.name(), tool.description(), tool.inputSchema(), arguments -> {
                    behavior.beforeTool(name, arguments);
                    JsonNode result = tool.execute(arguments);
                    if (result == null) {
                        throw new SkillExecutionException("工具返回 null");
                    }
                    toolResults.put(name, result.deepCopy());
                    behavior.afterTool(name, result);
                    return result;
                });
            }).toList();
        }

        private ToolCallback callback(String name, String description, String schema, Function<JsonNode, JsonNode> operation) {
            return new ToolCallback() {
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
                }

                public String call(String rawArguments) {
                    checkCancelled();
                    AssistantMessage.ToolCall requested = pendingCalls.stream()
                            .filter(call -> call.name().equals(name) && call.arguments().equals(rawArguments)).findFirst().orElseThrow();
                    pendingCalls.remove(requested);
                    long toolStart = System.nanoTime();
                    JsonNode arguments = mapper.createObjectNode();
                    ExecutionLog toolLog = log.with("round", round, "tool", name, "toolCallId", requested.id(), "channel", "TOOL");
                    if (toolFailed) {
                        String error = "前序工具已失败，本次调用未执行";
                        toolLog.info("TOOL_EXECUTION_SKIPPED", "reason", "PREVIOUS_TOOL_FAILED");
                        traces.add(new Trace(requested.id(), name, name, "FAILED", 0, round, arguments, null, error));
                        emit("TOOL_FAILED", "FAILED", behavior.toolLabel(name) + "未执行：" + error, name, requested.id());
                        return mapper.valueToTree(Map.of("error", error)).toString();
                    }
                    timing.enter(SkillTimingHistory.toolStage(name));
                    String phase = "ARGUMENTS";
                    try (var scope = toolLog.activate()) {
                        toolLog.info("TOOL_EXECUTION_STARTED", "argumentChars", rawArguments == null ? 0 : rawArguments.length());
                        arguments = parseObject(rawArguments);
                        emit("TOOL_STARTED", "RUNNING", "正在" + behavior.toolLabel(name), name, requested.id());
                        phase = "EXECUTION";
                        JsonNode output = operation.apply(arguments.deepCopy());
                        checkCancelled();
                        phase = "PRESENTATION";
                        String serialized = behavior.toolData(name, output).toString();
                        timing.leave();
                        traces.add(new Trace(requested.id(), name, name, "SUCCESS", elapsed(toolStart), round, arguments, output, null));
                        emit("TOOL_COMPLETED", "SUCCESS", "已完成：" + behavior.toolLabel(name), name, requested.id());
                        int rawChars = output.toString().length();
                        toolLog.info("TOOL_EXECUTION_COMPLETED", "elapsedMs", elapsed(toolStart), "resultChars", serialized.length(),
                                "rawResultChars", rawChars, "savedChars", rawChars - serialized.length());
                        return serialized;
                    } catch (CancellationException exception) {
                        throw exception;
                    } catch (RuntimeException exception) {
                        checkCancelled();
                        timing.leave();
                        toolFailed = true;
                        toolLog.failure("TOOL_EXECUTION_FAILED", exception, "elapsedMs", elapsed(toolStart), "phase", phase);
                        String error = "ARGUMENTS".equals(phase) ? "工具参数不是合法的 JSON 对象，本次调用未执行"
                                : "PRESENTATION".equals(phase) ? "工具已返回，但结果整理失败，请检查 TOOL_EXECUTION_FAILED 日志"
                                : behavior.toolError(name, exception);
                        traces.add(new Trace(requested.id(), name, name, "FAILED", elapsed(toolStart), round, arguments, null, error));
                        emit("TOOL_FAILED", "FAILED", behavior.toolLabel(name) + "失败：" + error, name, requested.id());
                        return mapper.valueToTree(Map.of("error", error)).toString();
                    } finally {
                        timing.leave();
                    }
                }
            };
        }

        private void validateCalls(List<AssistantMessage.ToolCall> calls, List<ToolCallback> callbacks) {
            Set<String> allowed = new HashSet<>(callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList());
            if (traces.size() + calls.size() > skill.maxToolCalls()) {
                throw new SkillExecutionException("超过 Skill 配置的工具调用次数上限");
            }
            for (AssistantMessage.ToolCall call : calls) {
                if (!allowed.contains(call.name()) || !"function".equals(call.type())) {
                    throw new SkillExecutionException("模型请求了未授权的工具，已阻止执行");
                }
                if (call.id() == null || call.id().isBlank() || !seenCallIds.add(call.id())) {
                    throw new SkillExecutionException("模型返回了空或重复的 tool_call_id");
                }
                if (call.arguments() == null || call.arguments().length() > 16384) {
                    throw new SkillExecutionException("模型工具参数为空或过长");
                }
            }
        }

        private JsonNode finish(String text) {
            String content = text == null ? "" : text.strip();
            if (content.startsWith("```json\n") && content.endsWith("```")) {
                content = content.substring(8, content.length() - 3).strip();
            }
            ObjectNode output;
            try {
                if (content.isEmpty() || content.length() > 65536) throw new AnswerFailure("EMPTY_OR_OVERSIZED_JSON");
                JsonNode parsed = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(content);
                if (parsed == null || !parsed.isObject()) throw new AnswerFailure("EXPECTED_JSON_OBJECT");
                if (parsed.has("name") && parsed.has("arguments") && skill.requiredOutputs().stream().noneMatch(parsed::has)) {
                    throw new AnswerFailure("TOOL_DECISION_IN_FINAL_ANSWER");
                }
                output = (ObjectNode) parsed;
            } catch (java.io.IOException exception) {
                boolean toolText = java.util.regex.Pattern.compile("<(?:tool_call|function)(?:\\s|>|=)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(content).find();
                throw new AnswerFailure(toolText ? "TEXT_TOOL_CALL_IN_FINAL_ANSWER" : "INVALID_JSON");
            }
            return validateOutput(output);
        }

        private JsonNode validateOutput(ObjectNode output) {
            JsonNode validated = behavior.finish(output);
            if (validated == null || !validated.isObject()) throw new SkillExecutionException("业务校验必须返回 JSON 对象");
            for (String name : skill.requiredOutputs()) {
                if (!validated.hasNonNull(name)) throw new SkillExecutionException("结果缺少 Skill 要求的字段: " + name);
            }
            return validated;
        }

        private Run result(String status, JsonNode output, String error) {
            log.with(behavior.diagnosticFields()).info("RUN_SUMMARY", "status", status, "elapsedMs", elapsed(start), "modelCalls", turns.size(),
                    "toolCalls", traces.size(), "contentEvents", contentSequence);
            boolean live = skill.tools().stream().anyMatch(name -> "LIVE".equals(behavior.resolveTool(name).dataMode()));
            boolean mock = skill.tools().stream().anyMatch(name -> "MOCK".equals(behavior.resolveTool(name).dataMode()));
            String mode = live ? (mock ? "LIVE_MODEL_MIXED_DATA" : "LIVE_MODEL_LIVE_DATA") : "LIVE_MODEL_MOCK_DATA";
            SkillTimingHistory.Report report = timing.report("SUCCESS".equals(status));
            timingHistory.record(runId, timingPlan, report, "SUCCESS".equals(status));
            log.info("RUN_TIMING", "actualMs", report.elapsedMs(), "estimatedMs", report.estimate().expectedMs(),
                    "sampleCount", report.estimate().sampleCount(), "predictionErrorMs", report.predictionErrorMs());
            return new Run(runId, skill.id(), skill.name(), status, startedAt, report.elapsedMs(), output,
                    List.copyOf(traces), error, model.modelName(), turns.size(), List.copyOf(turns), mode, report);
        }
    }

    private record ModelSignal(ChatResponse response, boolean finished) {}

    private String modelFailure(RuntimeException exception) {
        if (exception instanceof SkillExecutionException) return exception.getMessage();
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
                return "模型调用超时：响应空闲、单轮时限或 Skill 总时限已到，已取消本次等待，不会自动重复调用工具";
            }
        }
        cause = exception;
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof java.io.IOException) return "模型响应连接中断，未收到完整结果；请检查模型网关或代理连接后手动重试";
        }
        return "模型调用失败，请检查现有模型配置、网络或服务端日志";
    }

    private JsonNode parseObject(String json) {
        if (json == null || json.length() > 65536) {
            throw new SkillExecutionException("JSON 内容为空或过长");
        }
        try {
            JsonNode result = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (result == null || !result.isObject()) {
                throw new SkillExecutionException("模型必须返回 JSON 对象");
            }
            return result;
        } catch (java.io.IOException exception) {
            throw new SkillExecutionException("模型返回了无效 JSON", exception);
        }
    }

    private long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    public record Run(String runId, String skillId, String skillName, String status, String startedAt,
                      long durationMs, JsonNode output, List<Trace> traces, String error,
                      String model, int modelCalls, List<ModelTurn> modelTurns, String mode, SkillTimingHistory.Report timing) {
    }

    public record Progress(String runId, String skillId, int sequence, String type, String status, String message,
                           int modelRound, String tool, String toolCallId, long elapsedMs, String timestamp, SkillTimingHistory.Snapshot timing) {
    }

    public record ContentDelta(String runId, String skillId, int sequence, int modelRound, String text) {
    }

    public record Trace(String id, String name, String tool, String status, long durationMs,
                        int modelRound, JsonNode arguments, JsonNode output, String error) {
    }

    public record ModelTurn(int round, String status, long durationMs, String responseId, String model,
                            Integer promptTokens, Integer completionTokens, List<AssistantMessage.ToolCall> toolCalls) {
    }

    private static class IncompleteModelStreamException extends SkillExecutionException {
        private IncompleteModelStreamException() { super("模型流未完整结束，不能确认结果或执行工具"); }
    }

    public static class UnknownSkillException extends RuntimeException {
    }
}
