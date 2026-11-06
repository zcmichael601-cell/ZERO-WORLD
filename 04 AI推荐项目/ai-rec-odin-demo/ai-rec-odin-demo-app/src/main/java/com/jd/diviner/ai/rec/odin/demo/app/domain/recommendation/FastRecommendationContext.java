package com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public final class FastRecommendationContext {

    private FastRecommendationContext() {
    }

    public static JsonNode compact(JsonNode source) {
        ObjectNode result = source.deepCopy();
        result.retain(List.of("username", "displayName", "source", "dataStatus", "budget", "memories", "interests", "signals"));
        if (result.path("memories").isArray() && !result.path("memories").isEmpty()) {
            result.remove(List.of("interests", "signals"));
            var memories = result.putArray("memories");
            int count = 0;
            for (JsonNode memory : source.path("memories")) {
                if (count++ == 6) break;
                ObjectNode item = memory.deepCopy();
                item.retain(List.of("memory", "create_time", "update_time"));
                String text = item.path("memory").asText();
                if (text.length() > 400) item.put("memory", text.substring(0, 400)).put("truncated", true);
                memories.add(item);
            }
            result.put("partial", source.path("memories").size() > memories.size());
        }
        return result;
    }

    public static String searchText(JsonNode profile, JsonNode behaviors) {
        StringBuilder text = new StringBuilder();
        for (JsonNode data : List.of(profile, behaviors)) {
            if (data.path("memories").isArray() && !data.path("memories").isEmpty()) {
                data.path("memories").forEach(memory -> text.append(memory.path("memory").asText()).append('\n'));
            } else {
                for (String field : List.of("interests", "signals")) {
                    data.path(field).forEach(value -> text.append(value.asText()).append('\n'));
                }
            }
        }
        return text.toString();
    }
}
