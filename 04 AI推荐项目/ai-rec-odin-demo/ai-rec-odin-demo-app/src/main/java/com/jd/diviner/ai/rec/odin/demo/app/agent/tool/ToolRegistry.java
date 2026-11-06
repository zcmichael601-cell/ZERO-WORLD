package com.jd.diviner.ai.rec.odin.demo.app.agent.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    private final Map<String, SkillTool> tools;

    public ToolRegistry(List<SkillTool> registeredTools) {
        Map<String, SkillTool> collected = new LinkedHashMap<>();
        for (SkillTool tool : registeredTools) {
            if (collected.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("重复的 Tool: " + tool.name());
            }
        }
        tools = Collections.unmodifiableMap(collected);
    }

    public SkillTool require(String name) {
        SkillTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未注册的 Tool: " + name);
        }
        return tool;
    }

    public List<Map<String, String>> describe() {
        return tools.values().stream()
                .map(tool -> Map.of("name", tool.name(), "description", tool.description(), "dataMode", tool.dataMode())).toList();
    }
}
