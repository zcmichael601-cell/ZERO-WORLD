package com.jd.diviner.ai.rec.odin.demo.app.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public final class SkillDataView {

    private SkillDataView() {
    }

    public static JsonNode businessData(JsonNode data) {
        JsonNode result = data.deepCopy();
        if (result.isObject() && "MOCK".equals(result.path("source").asText())) {
            ((ObjectNode) result).remove(List.of("source", "notice"));
        }
        return result;
    }
}
