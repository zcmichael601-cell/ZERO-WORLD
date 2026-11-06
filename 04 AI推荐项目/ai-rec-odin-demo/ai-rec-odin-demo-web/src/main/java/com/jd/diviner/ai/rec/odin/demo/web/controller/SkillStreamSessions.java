package com.jd.diviner.ai.rec.odin.demo.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.web.vo.Result;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

public class SkillStreamSessions implements DisposableBean {
    private final SkillEngine engine;
    private final ObjectMapper mapper;
    private final Duration grace;
    private final Duration retention;
    private final int capacity;
    private final int maxEvents;
    private final int maxChars;
    private final Map<String, Session> sessions = new HashMap<>();

    public SkillStreamSessions(SkillEngine engine, ObjectMapper mapper) {
        this(engine, mapper, Duration.ofSeconds(30), Duration.ofMinutes(3), 16, 32768, 8 * 1024 * 1024);
    }

    public SkillStreamSessions(SkillEngine engine, ObjectMapper mapper, Duration grace, Duration retention,
                               int capacity, int maxEvents, int maxChars) {
        if (grace.isNegative() || grace.isZero() || retention.isNegative() || retention.isZero()
                || capacity < 1 || maxEvents < 1 || maxChars < 1) throw new IllegalArgumentException("会话限制必须为正值");
        this.engine = engine;
        this.mapper = mapper;
        this.grace = grace;
        this.retention = retention;
        this.capacity = capacity;
        this.maxEvents = maxEvents;
        this.maxChars = maxChars;
    }

    public synchronized Flux<ServerSentEvent<Object>> connect(String id, String skillId, JsonNode input,
                                                               boolean resume, ExecutionLog connectionLog) {
        validateId(id);
        Session session = sessions.get(id);
        if (session == null) {
            if (resume) throw new ResponseStatusException(HttpStatus.GONE, "会话不存在或已过期，不会自动重新执行 Skill");
            if (sessions.size() >= capacity) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "会话容量已满，请稍后手动重试");
            session = new Session(id, skillId, input.deepCopy(), connectionLog.with("sessionId", id));
            sessions.put(id, session);
        } else if (!session.skillId.equals(skillId) || !session.input.equals(input)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话参数不匹配，已拒绝重复执行");
        }
        Session existing = session;
        connectionLog.info("SESSION_ATTACHED", "sessionId", id, "resume", resume);
        return Flux.defer(existing::subscribe);
    }

    public void cancel(String id, String skillId) {
        validateId(id);
        Session session;
        synchronized (this) { session = sessions.get(id); }
        if (session != null && session.skillId.equals(skillId)) session.stop("会话已取消");
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("会话标识必须为 UUID");
        }
    }

    private synchronized void remove(String id, Session session) { sessions.remove(id, session); }

    @Override
    public void destroy() {
        java.util.List<Session> existing;
        synchronized (this) {
            existing = java.util.List.copyOf(sessions.values());
            sessions.clear();
        }
        existing.forEach(Session::dispose);
    }

    private final class Session {
        private final String id;
        private final String skillId;
        private final JsonNode input;
        private final ExecutionLog log;
        private final Sinks.Many<ServerSentEvent<Object>> replay = Sinks.many().replay().all();
        private Disposable worker;
        private Disposable disconnect;
        private Disposable expiry;
        private final Disposable deadline;
        private int subscribers;
        private int events;
        private int chars;
        private boolean started;
        private volatile boolean stopped;
        private boolean finished;
        private boolean capacityWarning;

        private Session(String id, String skillId, JsonNode input, ExecutionLog log) {
            this.id = id;
            this.skillId = skillId;
            this.input = input;
            this.log = log;
            long lifetimeMs = engine.clientTimeout().toMillis();
            log.info("SESSION_CREATED", "runBudgetMs", engine.runTimeout().toMillis(), "lifetimeMs", lifetimeMs,
                    "disconnectGraceMs", grace.toMillis(), "maxEvents", maxEvents, "maxChars", maxChars);
            deadline = Schedulers.parallel().schedule(() -> stop("会话超过总存活时限，已停止"), lifetimeMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            disconnect = Schedulers.parallel().schedule(() -> stop("会话未建立连接，已停止"), grace.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        private synchronized Flux<ServerSentEvent<Object>> subscribe() {
            if (disconnect != null) disconnect.dispose();
            subscribers++;
            if (!started && !finished) {
                started = true;
                worker = Schedulers.boundedElastic().schedule(this::execute);
            }
            log.info("SESSION_SUBSCRIBED", "subscribers", subscribers, "replayEvents", events);
            return replay.asFlux().doFinally(signal -> detach());
        }

        private synchronized void detach() {
            subscribers--;
            if (subscribers == 0 && !finished) {
                log.info("SESSION_RECONNECT_WAIT", "graceMs", grace.toMillis());
                disconnect = Schedulers.parallel().schedule(() -> {
                    synchronized (Session.this) {
                        if (subscribers == 0) stop("连接中断后未在重连窗口内恢复，会话已停止");
                    }
                }, grace.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }

        private void execute() {
            try (var scope = log.activate()) {
                SkillEngine.Run run = engine.executeStreaming(skillId, input,
                        progress -> publish("progress", progress), delta -> publish("content", delta), () -> stopped);
                publish("result", new Result<>("SUCCESS".equals(run.status()) ? 0 : 500, run, run.error() == null ? "" : run.error()));
                complete();
            } catch (CancellationException exception) {
                stop("会话已取消");
            } catch (RuntimeException exception) {
                log.failure("SESSION_FAILED", exception);
                stop("会话执行异常，未取得完整结果");
            }
        }

        private synchronized void publish(String type, Object data) {
            if (finished) throw new CancellationException("会话已结束");
            int size;
            try { size = mapper.writeValueAsString(data).length(); }
            catch (java.io.IOException exception) { throw new IllegalStateException("会话消息序列化失败", exception); }
            if (events >= maxEvents || size > maxChars - chars) {
                stop("会话消息超出缓存上限，已停止而非重新执行");
                throw new CancellationException("会话缓存已满");
            }
            if (!capacityWarning && (events >= maxEvents * 0.8 || chars + size >= maxChars * 0.8)) {
                capacityWarning = true;
                log.info("SESSION_CACHE_PRESSURE", "events", events, "chars", chars, "maxEvents", maxEvents, "maxChars", maxChars);
            }
            chars += size;
            replay.tryEmitNext(ServerSentEvent.builder(data).event(type).id(Integer.toString(++events)).build());
        }

        private synchronized void complete() {
            if (finished) return;
            finished = true;
            replay.tryEmitComplete();
            retain();
        }

        private synchronized void stop(String message) {
            if (finished) return;
            stopped = true;
            finished = true;
            log.info("SESSION_STOPPED", "reason", message);
            replay.tryEmitNext(ServerSentEvent.builder((Object) Map.of("message", message)).event("error").build());
            replay.tryEmitComplete();
            if (worker != null) worker.dispose();
            retain();
        }

        private void retain() {
            if (disconnect != null) disconnect.dispose();
            deadline.dispose();
            expiry = Schedulers.parallel().schedule(() -> remove(id, this), retention.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        private synchronized void dispose() {
            stop("服务正在关闭，会话已停止");
            if (expiry != null) expiry.dispose();
        }
    }
}
