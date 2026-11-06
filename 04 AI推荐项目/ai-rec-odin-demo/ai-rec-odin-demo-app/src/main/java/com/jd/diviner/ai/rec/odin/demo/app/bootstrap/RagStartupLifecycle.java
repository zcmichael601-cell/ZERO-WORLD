package com.jd.diviner.ai.rec.odin.demo.app.bootstrap;

import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagEmbeddingTransport;
import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagKnowledgeBase;

import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

public class RagStartupLifecycle implements SmartInitializingSingleton, ApplicationListener<ApplicationEvent>, Ordered {
    private final Map<String, RagKnowledgeBase> knowledgeBases;
    private final ConfigurableApplicationContext context;
    private final String startupId = UUID.randomUUID().toString();
    private final ExecutionLog log = ExecutionLog.current().with("startupId", startupId, "channel", "STARTUP");
    private final AtomicBoolean readyLogged = new AtomicBoolean();
    private volatile boolean initialized;
    private long warmupMs;

    public RagStartupLifecycle(Map<String, RagKnowledgeBase> knowledgeBases, ConfigurableApplicationContext context) {
        this.knowledgeBases = new TreeMap<>(knowledgeBases);
        this.context = context;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void afterSingletonsInstantiated() {
        warmUp();
    }

    private synchronized void warmUp() {
        if (initialized) return;
        long start = System.nanoTime();
        initialized = false;
        log.info("SERVICE_STARTUP_LOADING", "message", "正在预加载 RAG 索引，完成前不启动 HTTP 监听，请在控制台查看进度",
                "knowledgeBases", knowledgeBases.keySet(), "startupPhase", "BEFORE_WEB_SERVER_START");
        String current = null;
        try {
            if (knowledgeBases.isEmpty()) throw new IllegalStateException("未配置 RAG 知识库");
            for (var entry : knowledgeBases.entrySet()) {
                current = entry.getKey();
                RagKnowledgeBase knowledgeBase = entry.getValue();
                long indexStart = System.nanoTime();
                ExecutionLog indexLog = log.with("knowledgeBase", current);
                try (var scope = indexLog.activate()) {
                    indexLog.info("RAG_STARTUP_LOADING", "message", "正在加载资料并构建向量索引",
                            "embeddingModel", knowledgeBase.status().embeddingModel(), "reused", knowledgeBase.status().ready());
                    knowledgeBase.ensureReady();
                    RagKnowledgeBase.Status status = knowledgeBase.status();
                    if (!status.ready()) throw new IllegalStateException("RAG 索引尚未就绪");
                    indexLog.info("RAG_STARTUP_READY", "message", "RAG 索引预加载完成", "elapsedMs", ExecutionLog.elapsed(indexStart),
                            "documents", status.documents().size(), "chunks", status.chunks(), "indexVersion", status.version());
                }
            }
            warmupMs = ExecutionLog.elapsed(start);
            initialized = true;
            log.info("RAG_STARTUP_COMPLETED", "message", "全部 RAG 索引已就绪，等待应用完成启动", "elapsedMs", warmupMs);
        } catch (RuntimeException exception) {
            String failureSummary = RagEmbeddingTransport.failureSummary(exception);
            log.failure("SERVICE_STARTUP_FAILED", exception, "message", "RAG 预加载失败，服务启动终止",
                    "knowledgeBase", current, "elapsedMs", ExecutionLog.elapsed(start), "failureSummary", failureSummary);
            var safeCause = new IllegalStateException(failureSummary);
            safeCause.setStackTrace(exception.getStackTrace());
            throw new IllegalStateException("RAG 启动预加载失败，服务未就绪；knowledgeBase=" + current
                    + "，原因=" + failureSummary + "；请根据 startupId=" + startupId + " 排查服务端日志", safeCause);
        }
    }

    @Override
    public void onApplicationEvent(ApplicationEvent applicationEvent) {
        if (applicationEvent instanceof WebServerInitializedEvent serverEvent && serverEvent.getApplicationContext() == context) {
            requireInitialized();
            log.info("HTTP_SERVER_STARTED", "message", "RAG 预加载完成，HTTP 已监听；等待 SERVICE_READY 确认应用就绪",
                    "port", serverEvent.getWebServer().getPort(), "ragWarmupMs", warmupMs,
                    "contextPath", context.getEnvironment().getProperty("server.servlet.context-path", ""));
        }
        if (!(applicationEvent instanceof ApplicationReadyEvent event)) return;
        if (event.getApplicationContext() != context) return;
        requireInitialized();
        if (!readyLogged.compareAndSet(false, true)) return;
        var environment = context.getEnvironment();
        log.info("SERVICE_READY", "message", "服务启动成功，商品 RAG 已就绪，可使用 AI 对话和 Skill 实验室", "status", "READY",
                "application", environment.getProperty("spring.application.name", "ai-rec-odin-demo"),
                "port", environment.getProperty("local.server.port", environment.getProperty("server.port")),
                "contextPath", environment.getProperty("server.servlet.context-path", ""),
                "startupMs", event.getTimeTaken() == null ? Math.max(0, event.getTimestamp() - context.getStartupDate()) : event.getTimeTaken().toMillis(),
                "ragWarmupMs", warmupMs, "knowledgeBases", knowledgeBases.keySet());
    }

    private void requireInitialized() {
        if (!initialized || knowledgeBases.values().stream().anyMatch(knowledgeBase -> !knowledgeBase.status().ready())) {
            throw new IllegalStateException("RAG 启动预加载未完成，不能声明服务就绪");
        }
    }
}
