package com.jd.diviner.ai.rec.odin.demo.app.domain.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class BeijingTravelValidator {

    private BeijingTravelValidator() {
    }

    public static void validate(ObjectNode output, Map<String, JsonNode> results, JsonNode input) {
        JsonNode weather = results.get("get_beijing_weather");
        JsonNode attractions = results.get("get_beijing_hot_attractions");
        if (weather == null || attractions == null || !"LIVE".equals(weather.at("/source/dataMode").asText())
                || !"LIVE".equals(attractions.at("/source/dataMode").asText())) {
            throw invalid("缺少真实天气或榜单工具结果");
        }
        JsonNode recommendations = output.path("recommendations");
        if (!recommendations.isArray() || recommendations.size() > 4) throw invalid("recommendations 应为 0~4 项数组");
        Set<String> seen = new HashSet<>();
        for (JsonNode recommendation : recommendations) {
            for (String field : Set.of("attractionId", "date", "interestReason", "weatherReason", "precautions", "alternative")) {
                if (!recommendation.path(field).isTextual() || recommendation.path(field).asText().isBlank()) {
                    throw invalid("推荐缺少字段: " + field);
                }
            }
            String id = recommendation.path("attractionId").asText();
            if (!seen.add(id)) throw invalid("重复推荐同一景点");
            JsonNode attraction = find(attractions.path("attractions"), "id", id);
            JsonNode day = find(weather.path("forecast"), "date", recommendation.path("date").asText());
            if (attraction == null || day == null) throw invalid("推荐引用了未查询的景点或预报日期");
            ((ObjectNode) recommendation).set("attraction", attraction.deepCopy());
            ((ObjectNode) recommendation).set("weather", day.deepCopy());
        }
        JsonNode tips = output.path("travelTips");
        if (!tips.isArray() || tips.isEmpty() || tips.size() > 6) throw invalid("travelTips 应为 1~6 项数组");
        for (JsonNode tip : tips) {
            if (!tip.isTextual() || tip.asText().isBlank()) throw invalid("出行提醒不能为空");
        }
        output.set("weather", weather.deepCopy());
        output.set("attractions", attractions.deepCopy());
        output.put("interests", input.path("interests").asText());
    }

    private static JsonNode find(JsonNode items, String field, String value) {
        for (JsonNode item : items) {
            if (value.equals(item.path(field).asText())) return item;
        }
        return null;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("北京出游推荐无效：" + reason);
    }
}
