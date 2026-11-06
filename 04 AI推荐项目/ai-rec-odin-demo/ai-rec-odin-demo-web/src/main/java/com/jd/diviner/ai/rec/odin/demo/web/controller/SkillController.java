package com.jd.diviner.ai.rec.odin.demo.web.controller;

import com.jd.diviner.ai.rec.odin.demo.app.domain.user.UserIdentifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import com.jd.diviner.ai.rec.odin.demo.app.tools.user.DemoUserTools;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillDefinition;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillTimingHistory;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import com.jd.diviner.ai.rec.odin.demo.web.vo.Result;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/skills")
@Import(SkillStreamingConfiguration.class)
public class SkillController {

    private final SkillCatalog catalog;
    private final SkillEngine engine;
    private final ToolRegistry registry;
    private final SkillStreamSessions sessions;
    @org.springframework.beans.factory.annotation.Value("${odin.memory.user-pins:315c70c8f4a25681}")
    private String userUuids = "315c70c8f4a25681";

    public SkillController(SkillCatalog catalog, SkillEngine engine, ToolRegistry registry) {
        this(catalog, engine, registry, new SkillStreamSessions(engine, new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SkillController(SkillCatalog catalog, SkillEngine engine, ToolRegistry registry, SkillStreamSessions sessions) {
        this.catalog = catalog;
        this.engine = engine;
        this.registry = registry;
        this.sessions = sessions;
    }

    @GetMapping
    public Result<List<SkillDefinition>> list() {
        return new Result<>(catalog.enabledSkills());
    }

    @GetMapping("/tools")
    public Result<List<Map<String, String>>> tools() {
        return new Result<>(registry.describe());
    }

    @GetMapping("/runtime")
    public Result<Map<String, String>> runtime() {
        return new Result<>(Map.of("model", engine.modelName(), "mode", "LIVE_MODEL_MIXED_DATA",
                "runTimeoutMs", Long.toString(engine.runTimeout().toMillis()),
                "clientTimeoutMs", Long.toString(engine.clientTimeout().toMillis()),
                "description", "模型通过工具查询画像、行为、商品及出游信息，分析并生成推荐"));
    }

    @GetMapping("/users")
    public Result<List<UserChoice>> users() {
        return new Result<>(java.util.Arrays.stream(userUuids.split(",")).map(String::strip).filter(userId -> !userId.isBlank())
                .map(UserIdentifier::validate).distinct().map(userId -> new UserChoice(userId, userId, "AI_MEMORY")).toList());
    }

    public record UserChoice(String username, String displayName, String source) {}

    @GetMapping("/demo-users")
    public Result<List<DemoUserTools.MockUser>> demoUsers() {
        return new Result<>(DemoUserTools.users());
    }

    @GetMapping("/{skillId}/instructions")
    public Result<Map<String, String>> instructions(@PathVariable String skillId) {
        if (catalog.findEnabled(skillId) == null) {
            throw new SkillEngine.UnknownSkillException();
        }
        return new Result<>(Map.of("content", catalog.instructions(skillId)));
    }

    @GetMapping("/{skillId}/estimate")
    public Result<SkillTimingHistory.Estimate> estimate(@PathVariable String skillId,
            @RequestParam(defaultValue = "REAL") String userDataMode) {
        return new Result<>(engine.estimate(skillId, userDataMode));
    }

    @PostMapping("/{skillId}/run")
    public ResponseEntity<Result<SkillEngine.Run>> run(@PathVariable String skillId, @RequestBody JsonNode request) {
        String requestId = UUID.randomUUID().toString();
        ExecutionLog log = ExecutionLog.current().with("requestId", requestId, "skillId", skillId, "channel", "HTTP");
        long start = System.nanoTime();
        try (var scope = log.activate()) {
            log.info("HTTP_REQUEST_STARTED", "streaming", false);
            SkillEngine.Run run = engine.execute(skillId, requestInput(request));
            boolean success = "SUCCESS".equals(run.status());
            log.info("HTTP_REQUEST_COMPLETED", "runId", run.runId(), "status", run.status(), "elapsedMs", ExecutionLog.elapsed(start));
            return ResponseEntity.status(success ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("X-Request-Id", requestId).body(runResult(run));
        } catch (RuntimeException exception) {
            log.failure("HTTP_REQUEST_REJECTED", exception, "elapsedMs", ExecutionLog.elapsed(start));
            throw exception;
        }
    }

    @PostMapping(path = "/{skillId}/session-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<Object>>> streamWithSession(@PathVariable String skillId, @RequestBody JsonNode request,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String sessionId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean resume) {
        if (sessionId == null) throw new IllegalArgumentException("缺少会话标识");
        String requestId = UUID.randomUUID().toString();
        JsonNode input = requestInput(request);
        engine.validateInput(skillId, input);
        ExecutionLog log = ExecutionLog.current().with("requestId", requestId, "skillId", skillId, "channel", "SSE");
        Flux<ServerSentEvent<Object>> events = sessions.connect(sessionId, skillId, input, resume, log)
                .doFinally(signal -> log.info("SSE_TERMINATED", "sessionId", sessionId, "signal", signal.name()));
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").header("X-Request-Id", requestId).header("X-Skill-Session", sessionId).body(events);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{skillId}/sessions/{sessionId}")
    public ResponseEntity<Void> cancelSession(@PathVariable String skillId, @PathVariable String sessionId) {
        sessions.cancel(sessionId, skillId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{skillId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<Object>>> stream(@PathVariable String skillId, @RequestBody JsonNode request) {
        String requestId = UUID.randomUUID().toString();
        ExecutionLog log = ExecutionLog.current().with("requestId", requestId, "skillId", skillId, "channel", "SSE");
        long start = System.nanoTime();
        log.info("HTTP_REQUEST_STARTED", "streaming", true);
        JsonNode input;
        try {
            input = requestInput(request);
            engine.validateInput(skillId, input);
        } catch (RuntimeException exception) {
            log.failure("HTTP_REQUEST_REJECTED", exception, "elapsedMs", ExecutionLog.elapsed(start));
            throw exception;
        }
        AtomicReference<String> runId = new AtomicReference<>();
        AtomicInteger progressEvents = new AtomicInteger();
        AtomicInteger contentEvents = new AtomicInteger();
        Flux<ServerSentEvent<Object>> events = Flux.<ServerSentEvent<Object>>create(sink -> {
            log.info("SSE_SUBSCRIBED", "elapsedMs", ExecutionLog.elapsed(start));
            var task = Schedulers.boundedElastic().schedule(() -> {
                try (var scope = log.activate()) {
                    log.info("SSE_WORKER_STARTED", "elapsedMs", ExecutionLog.elapsed(start));
                    SkillEngine.Run run = engine.executeStreaming(skillId, input,
                            progress -> {
                                runId.compareAndSet(null, progress.runId());
                                progressEvents.incrementAndGet();
                                sink.next(event("progress", progress));
                            }, delta -> {
                                contentEvents.incrementAndGet();
                                sink.next(event("content", delta));
                            }, sink::isCancelled);
                    if (!sink.isCancelled()) {
                        sink.next(event("result", runResult(run)));
                        log.info("SSE_RESULT_ENQUEUED", "runId", run.runId(), "status", run.status(), "elapsedMs", ExecutionLog.elapsed(start));
                        sink.complete();
                    }
                } catch (CancellationException exception) {
                    log.info("SSE_WORKER_CANCELLED", "runId", runId.get(), "elapsedMs", ExecutionLog.elapsed(start));
                    sink.complete();
                } catch (RuntimeException exception) {
                    log.failure("SSE_WORKER_FAILED", exception, "runId", runId.get(), "elapsedMs", ExecutionLog.elapsed(start));
                    sink.error(exception);
                }
            });
            sink.onDispose(task);
        }, FluxSink.OverflowStrategy.BUFFER).timeout(engine.runTimeout().plusSeconds(30))
                .doOnError(error -> log.failure("SSE_ERROR", error, "runId", runId.get(), "elapsedMs", ExecutionLog.elapsed(start)))
                .doFinally(signal -> log.info("SSE_TERMINATED", "runId", runId.get(), "signal", signal.name(),
                        "elapsedMs", ExecutionLog.elapsed(start), "progressEvents", progressEvents.get(), "contentEvents", contentEvents.get()))
                .onErrorResume(error -> Flux.just(event("error", Map.of("message", error instanceof TimeoutException
                        ? "执行进度流超时，连接已关闭；未收到完整结果" : "执行进度流异常，未收到完整结果，请重试"))));
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").header("X-Request-Id", requestId).body(events);
    }

    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    private static Result<SkillEngine.Run> runResult(SkillEngine.Run run) {
        return new Result<>("SUCCESS".equals(run.status()) ? 0 : 500, run, run.error() == null ? "" : run.error());
    }

    private JsonNode requestInput(JsonNode request) {
        JsonNode input = request.path("input");
        JsonNode username = input.path("username");
        if (!username.isMissingNode() && (!username.isTextual() || username.asText().isBlank()
                || username.asText().length() > 64 || username.asText().chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("用户标识必须为 1~64 个字符，且不能包含控制字符；真实用户请填写 UUID");
        }
        // 后续屏会带上最多 100 条会话曝光，商品名较长时 16KB 到第 4 屏就会被拒绝。
        if (request.toString().length() > 131072) {
            throw new IllegalArgumentException("输入内容过长");
        }
        if (username.isTextual()) {
            ((ObjectNode) input).put("username", UserIdentifier.validate(username.asText()));
        }
        return input;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> invalidInput(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(new Result<>(400, null, exception.getMessage()));
    }

    @ExceptionHandler(SkillEngine.UnknownSkillException.class)
    public ResponseEntity<Result<Void>> unknownSkill() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(new Result<>(404, null, "Skill 不存在或未启用"));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Result<Void>> sessionFailure(org.springframework.web.server.ResponseStatusException exception) {
        int status = exception.getStatusCode().value();
        String message = switch (status) {
            case 410 -> "会话不存在或已过期，不会自动重新执行 Skill";
            case 409 -> "会话参数不匹配，已拒绝重复执行";
            case 429 -> "会话容量已满，请稍后手动重试";
            default -> "会话请求未能完成";
        };
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new Result<>(status, null, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> invalidJson() {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(new Result<>(400, null, "请求体必须为有效 JSON"));
    }
}
