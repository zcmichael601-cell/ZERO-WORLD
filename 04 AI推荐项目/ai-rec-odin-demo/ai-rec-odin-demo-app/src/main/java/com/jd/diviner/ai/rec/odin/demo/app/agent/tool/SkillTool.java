package com.jd.diviner.ai.rec.odin.demo.app.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface SkillTool {

    String name();

    String description();

    String inputSchema();

    default String dataMode() { return "MOCK"; }

    JsonNode execute(JsonNode arguments);
}
