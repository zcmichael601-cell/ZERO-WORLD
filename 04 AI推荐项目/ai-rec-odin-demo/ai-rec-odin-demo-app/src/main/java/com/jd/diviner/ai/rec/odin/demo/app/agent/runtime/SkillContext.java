package com.jd.diviner.ai.rec.odin.demo.app.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillDefinition;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

public record SkillContext(ObjectMapper mapper, SkillDefinition skill, JsonNode input, String instructions,
                           ToolRegistry registry, Map<String, JsonNode> toolResults, ExecutionLog log,
                           BiConsumer<String, JsonNode> toolInvoker, Consumer<String> stageCompleted,
                           ProgressEmitter events, IntSupplier currentRound, LongSupplier elapsedMillis, Runnable checkpoint) {
    public SkillContext {
        toolResults = Collections.unmodifiableMap(toolResults);
    }

    public void invokeTool(String name, JsonNode arguments) { toolInvoker.accept(name, arguments); }
    public void emit(String type, String status, String message) { events.emit(type, status, message); }
    public int round() { return currentRound.getAsInt(); }
    public long elapsedMs() { return elapsedMillis.getAsLong(); }
    public void checkActive() { checkpoint.run(); }

    @FunctionalInterface
    public interface ProgressEmitter {
        void emit(String type, String status, String message);
    }
}
