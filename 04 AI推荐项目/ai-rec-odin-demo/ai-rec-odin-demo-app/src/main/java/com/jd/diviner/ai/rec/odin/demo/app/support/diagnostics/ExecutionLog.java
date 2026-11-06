package com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

public final class ExecutionLog {
    private static final Logger LOGGER = LogManager.getLogger(ExecutionLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ThreadLocal<ExecutionLog> ACTIVE = new ThreadLocal<>();
    private final Map<String, Object> context;

    private ExecutionLog(Map<String, Object> context) {
        this.context = context;
    }

    public static ExecutionLog current() {
        ExecutionLog current = ACTIVE.get();
        return current == null ? new ExecutionLog(Map.of()) : current;
    }

    public ExecutionLog with(Object... fields) {
        Map<String, Object> values = new LinkedHashMap<>(context);
        putFields(values, fields);
        return new ExecutionLog(values);
    }

    public Scope activate() {
        ExecutionLog previous = ACTIVE.get();
        ACTIVE.set(this);
        return () -> {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        };
    }

    public void info(String event, Object... fields) {
        if (ConsoleLoggingBootstrap.diagnosticConsoleEnabled()) console("INFO", format(event, fields));
        else if (LOGGER.isInfoEnabled()) LOGGER.info(format(event, fields));
    }

    public static void reportConfiguration() {
        boolean console = ConsoleLoggingBootstrap.diagnosticConsoleEnabled();
        boolean enabled = console || LOGGER.isInfoEnabled();
        String message = current().format(enabled ? "AGENT_LOGGING_READY" : "AGENT_LOGGING_DISABLED",
                "channel", "LOGGING", "loggingFacade", console ? "DIRECT_STDOUT" : "LOG4J2", "logger", LOGGER.getName(),
                "loggerImplementation", LOGGER.getClass().getName(),
                "loggerFactory", LogManager.getFactory().getClass().getName(),
                "consoleLevel", System.getProperty("console_log_level", "OFF"),
                "dongConsoleEnabled", System.getProperty("donglog_console_enabled", "false"),
                "diagnosticOutput", console ? "DIRECT_CONSOLE" : "LOGGER",
                "loggerInfoEnabled", LOGGER.isInfoEnabled(),
                "infoEnabled", enabled,
                "message", console ? "RAG/Skill 诊断日志直接输出到当前进程标准输出，即 IDEA Run/Debug Console；不依赖平台日志级别或Appender"
                        : enabled ? "Skill 详细诊断日志已启用，请按 agent_diag / requestId / runId 检索"
                        : "Skill INFO 诊断日志被关闭，请将 ExecutionLog 日志级别设为 INFO，并检查日志绑定和输出配置");
        if (console) console("INFO", message);
        else if (enabled) LOGGER.info(message);
        else LOGGER.warn(message);
    }

    public void failure(String event, Throwable error, Object... fields) {
        ExecutionLog details = with(fields);
        var causes = new ArrayList<Map<String, Object>>();
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            var frames = new ArrayList<String>();
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (frames.size() == 8) break;
                frames.add(frame.toString());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", cause.getClass().getName());
            item.put("frames", frames);
            if (cause instanceof RestClientResponseException response) item.put("httpStatus", response.getStatusCode().value());
            if (cause instanceof WebClientResponseException response) item.put("httpStatus", response.getStatusCode().value());
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) item.put("category", "TIMEOUT");
            else if (cause instanceof java.io.IOException) item.put("category", "IO");
            causes.add(item);
        }
        String message = details.format(event, "causes", causes);
        if (ConsoleLoggingBootstrap.diagnosticConsoleEnabled()) console("WARN", message);
        else LOGGER.warn(message);
    }

    private static void console(String level, String message) {
        byte[] bytes = (java.time.OffsetDateTime.now() + " " + level + " " + message + System.lineSeparator())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var output = System.out;
        output.write(bytes, 0, bytes.length);
        output.flush();
    }

    private String format(String event, Object... fields) {
        Map<String, Object> values = new LinkedHashMap<>(context);
        values.put("event", event);
        values.put("pid", ProcessHandle.current().pid());
        values.put("thread", Thread.currentThread().getName());
        putFields(values, fields);
        try {
            return "agent_diag " + MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            return "agent_diag {\"event\":\"LOG_SERIALIZATION_FAILED\"}";
        }
    }

    private static void putFields(Map<String, Object> values, Object... fields) {
        if (fields.length % 2 != 0) throw new IllegalArgumentException("日志字段必须成对提供");
        for (int index = 0; index < fields.length; index += 2) {
            Object value = fields[index + 1];
            if (value instanceof String text && text.length() > 256) value = text.substring(0, 256);
            values.put((String) fields[index], value);
        }
    }

    public static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
