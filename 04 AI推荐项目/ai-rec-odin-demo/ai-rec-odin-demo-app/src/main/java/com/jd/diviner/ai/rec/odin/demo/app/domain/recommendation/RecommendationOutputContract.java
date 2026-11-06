package com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

public final class RecommendationOutputContract {
    private RecommendationOutputContract() {}

    public static ObjectNode selectionSchema(ObjectMapper mapper) {
        var schema = schema(mapper);
        var comparison = schema.path("properties").path("products").path("items").path("properties").path("comparison");
        for (String field : List.of("strengths", "limitations", "dimensions")) {
            var claim = (ObjectNode) comparison.path("properties").path(field).path("items");
            var properties = (ObjectNode) claim.path("properties");
            properties.remove("quote");
            properties.set("quoteId", mapper.createObjectNode().put("type", "string").put("minLength", 2)
                    .put("maxLength", 16).put("description", "选择同一evidenceId的quoteChoices.id；不要输出quote，原文由服务端填充"));
            var required = claim.putArray("required");
            properties.fieldNames().forEachRemaining(required::add);
        }
        return schema;
    }

    public static ObjectNode schema(ObjectMapper mapper) {
        var text = mapper.createObjectNode().put("type", "string").put("minLength", 1).put("maxLength", 1500);
        var score = mapper.createObjectNode().put("type", "integer").put("minimum", 0).put("maximum", 100);
        var claim = object(mapper, "text", text, "evidenceId", text, "quote",
                mapper.createObjectNode().put("type", "string").put("minLength", 6).put("maxLength", 500));
        var dimension = claim.deepCopy();
        ((ObjectNode) dimension.path("properties")).set("dimension", text.deepCopy());
        dimension.withArray("required").add("dimension");
        var status = text.deepCopy();
        status.set("enum", mapper.valueToTree(List.of("SUPPORTED", "PARTIAL_EVIDENCE", "INSUFFICIENT_EVIDENCE")));
        var limits = ProductComparisonValidator.limits(true);
        var comparison = object(mapper, "status", status, "fit", text, "uncertainty", text,
                "strengths", array(mapper, claim, limits.get("maxStrengths")),
                "limitations", array(mapper, claim, limits.get("maxLimitations")),
                "dimensions", array(mapper, dimension, limits.get("maxDimensions")));
        var product = object(mapper, "productId", text, "score", score, "reason", text, "comparison", comparison);
        var need = object(mapper, "category", text, "title", text, "evidence", text, "priority", text, "confidence", score);
        return object(mapper, "summary", text, "comparisonSummary", text, "needs", array(mapper, need, 12),
                "products", array(mapper, product, limits.get("maxProducts")));
    }

    private static ObjectNode object(ObjectMapper mapper, Object... fields) {
        var result = mapper.createObjectNode().put("type", "object");
        var properties = result.putObject("properties");
        var required = result.putArray("required");
        for (int index = 0; index < fields.length; index += 2) {
            String name = (String) fields[index];
            properties.set(name, ((JsonNode) fields[index + 1]).deepCopy());
            required.add(name);
        }
        return result;
    }

    private static ObjectNode array(ObjectMapper mapper, JsonNode items, int maximum) {
        var result = mapper.createObjectNode().put("type", "array").put("maxItems", maximum);
        result.set("items", items.deepCopy());
        return result;
    }

    public static List<Issue> inspect(JsonNode output, JsonNode schema) {
        List<Issue> issues = new ArrayList<>();
        inspect(output, schema, "", issues);
        if (output.path("products").isArray()) {
            for (int index = 0; index < output.path("products").size() && issues.size() < 32; index++) {
                JsonNode comparison = output.path("products").get(index).path("comparison");
                if (!comparison.path("strengths").isArray() || !comparison.path("limitations").isArray()
                        || !comparison.path("dimensions").isArray()) continue;
                int claims = comparison.path("strengths").size() + comparison.path("limitations").size() + comparison.path("dimensions").size();
                String status = comparison.path("status").asText();
                if (("INSUFFICIENT_EVIDENCE".equals(status) && claims != 0)
                        || ("PARTIAL_EVIDENCE".equals(status) && claims == 0)
                        || ("SUPPORTED".equals(status) && (comparison.path("strengths").isEmpty()
                        || comparison.path("limitations").isEmpty() || comparison.path("dimensions").isEmpty()))) {
                    issues.add(new Issue("/products/" + index + "/comparison/status", "EVIDENCE_STATUS_CONFLICT"));
                }
            }
        }
        return List.copyOf(issues);
    }

    private static void inspect(JsonNode value, JsonNode schema, String path, List<Issue> issues) {
        if (issues.size() >= 32) return;
        String type = schema.path("type").asText();
        boolean valid = switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber() && value.canConvertToInt();
            default -> false;
        };
        if (!valid) { issues.add(new Issue(path, "EXPECTED_" + type.toUpperCase(java.util.Locale.ROOT))); return; }
        switch (type) {
            case "object" -> schema.path("properties").fields().forEachRemaining(field ->
                    inspect(value.path(field.getKey()), field.getValue(), path + "/" + field.getKey(), issues));
            case "array" -> {
                if (value.size() < schema.path("minItems").asInt()) issues.add(new Issue(path, "MIN_ITEMS_NOT_MET"));
                if (value.size() > schema.path("maxItems").asInt()) issues.add(new Issue(path, "MAX_ITEMS_EXCEEDED"));
                for (int index = 0; index < value.size() && issues.size() < 32; index++) {
                    inspect(value.get(index), schema.path("items"), path + "/" + index, issues);
                }
            }
            case "string" -> {
                if (value.asText().isBlank() || value.asText().length() < schema.path("minLength").asInt()
                        || value.asText().length() > schema.path("maxLength").asInt()) issues.add(new Issue(path, "TEXT_LENGTH_INVALID"));
                if (schema.has("enum") && issues.size() < 32) {
                    boolean known = false;
                    for (JsonNode allowed : schema.path("enum")) if (allowed.equals(value)) known = true;
                    if (!known) issues.add(new Issue(path, "INVALID_ENUM"));
                }
            }
            case "integer" -> {
                if (value.asInt() < schema.path("minimum").asInt() || value.asInt() > schema.path("maximum").asInt())
                    issues.add(new Issue(path, "SCORE_OUT_OF_RANGE"));
            }
            default -> throw new IllegalStateException("不支持的输出契约类型");
        }
    }

    public record Issue(String path, String reason) {}
}
