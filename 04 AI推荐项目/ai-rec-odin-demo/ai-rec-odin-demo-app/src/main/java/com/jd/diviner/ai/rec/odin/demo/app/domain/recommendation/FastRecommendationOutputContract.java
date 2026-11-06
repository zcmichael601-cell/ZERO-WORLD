package com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;

public final class FastRecommendationOutputContract {
    private FastRecommendationOutputContract() {}

    public static ObjectNode schema(ObjectMapper mapper, Collection<JsonNode> candidates) {
        var schema = mapper.createObjectNode().put("type", "object");
        schema.putArray("required").add("summary").add("needs").add("products");
        var properties = schema.putObject("properties");
        properties.set("summary", text(mapper, 40));
        properties.putObject("needs").put("type", "array").put("maxItems", 0);
        var products = properties.putObject("products").put("type", "array")
                .put("minItems", candidates.isEmpty() ? 0 : 1).put("maxItems", Math.min(2, candidates.size()));
        var product = products.putObject("items").put("type", "object");
        product.putArray("required").add("productId").add("score").add("reason");
        var fields = product.putObject("properties");
        var productId = text(mapper, 64);
        var allowed = productId.putArray("enum");
        candidates.forEach(candidate -> allowed.add(candidate.path("id").asText()));
        fields.set("productId", productId);
        fields.putObject("score").put("type", "integer").put("minimum", 0).put("maximum", 100);
        fields.set("reason", text(mapper, 25));
        return schema;
    }

    private static ObjectNode text(ObjectMapper mapper, int maximum) {
        return mapper.createObjectNode().put("type", "string").put("minLength", 1).put("maxLength", maximum);
    }
}
