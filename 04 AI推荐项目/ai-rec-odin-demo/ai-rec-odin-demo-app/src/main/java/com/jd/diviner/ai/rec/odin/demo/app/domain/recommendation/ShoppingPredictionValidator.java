package com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Set;

public final class ShoppingPredictionValidator {

    private static final Set<String> ACTIONS = Set.of("browse", "compare", "favorite", "add_to_cart", "purchase", "repurchase");
    private static final Set<String> PROFILE_PATHS = Set.of("/interests", "/budget", "/segment");

    private ShoppingPredictionValidator() {
    }

    public static void validate(JsonNode output, Map<String, JsonNode> toolResults) {
        if (output.has("products") || output.has("needs")) {
            throw invalid("购物行为预测不能包含商品推荐或旧版需求结果");
        }
        requireArray(output.path("predictions"), 0, 3);
        for (JsonNode prediction : output.path("predictions")) {
            requireText(prediction, "action", "category", "description", "timeWindow", "reasoning", "alternative");
            if (!ACTIONS.contains(prediction.path("action").asText())) {
                throw invalid("不支持的购物动作");
            }
            validateInsight(prediction, toolResults);
        }
        requireArray(output.path("preferences"), 0, 4);
        for (JsonNode preference : output.path("preferences")) {
            requireText(preference, "dimension", "preference", "reasoning");
            validateInsight(preference, toolResults);
        }
        requireArray(output.path("uncertainties"), 1, 4);
        for (JsonNode uncertainty : output.path("uncertainties")) {
            if (!uncertainty.isTextual() || uncertainty.asText().isBlank()) {
                throw invalid("缺少有效的不确定性说明");
            }
        }
    }

    private static void validateInsight(JsonNode insight, Map<String, JsonNode> toolResults) {
        JsonNode confidence = insight.path("confidence");
        if (!confidence.isIntegralNumber() || !confidence.canConvertToInt() || confidence.asInt() < 0 || confidence.asInt() > 100) {
            throw invalid("信心分必须为 0~100 的整数");
        }
        requireArray(insight.path("evidence"), 1, 4);
        for (JsonNode evidence : insight.path("evidence")) {
            requireText(evidence, "source", "path");
            String source = evidence.path("source").asText();
            String path = evidence.path("path").asText();
            boolean allowed = "get_user_profile".equals(source) ? PROFILE_PATHS.contains(path) || path.matches("/memories/(0|[1-9][0-9]{0,3})/memory")
                    : "get_user_behaviors".equals(source) && path.matches("/signals/(0|[1-9][0-9]{0,3})");
            JsonNode result = toolResults.get(source);
            if (!allowed || result == null) {
                throw invalid("不允许的证据来源或路径");
            }
            JsonNode value = result.at(path);
            if (value.isMissingNode() || value.isNull() || value.isArray() && value.isEmpty() || value.isTextual() && value.asText().isBlank()) {
                throw invalid("引用的工具证据不存在");
            }
            ((ObjectNode) evidence).set("value", value.deepCopy());
        }
    }

    private static void requireArray(JsonNode value, int minimum, int maximum) {
        if (!value.isArray() || value.size() < minimum || value.size() > maximum) {
            throw invalid("预测结果数组长度不符合 Skill 要求");
        }
    }

    private static void requireText(JsonNode node, String... fields) {
        for (String field : fields) {
            if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) {
                throw invalid("缺少有效字段: " + field);
            }
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("购物预测结果无效：" + message);
    }
}
