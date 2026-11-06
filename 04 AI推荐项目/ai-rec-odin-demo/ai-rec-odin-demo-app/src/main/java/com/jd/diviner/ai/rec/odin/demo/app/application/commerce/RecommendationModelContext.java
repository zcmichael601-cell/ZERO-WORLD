package com.jd.diviner.ai.rec.odin.demo.app.application.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillDataView;
import java.util.List;

public final class RecommendationModelContext {
    private RecommendationModelContext() {}

    public static JsonNode toolData(String skillId, String tool, JsonNode output) {
        JsonNode data = SkillDataView.businessData(output);
        if (!("shopping-recommendation".equals(skillId) || "shopping-recommendation-fast".equals(skillId)) || !data.isObject()) return data;
        if ("search_products".equals(tool)) {
            for (JsonNode product : data.path("products")) {
                if (product instanceof ObjectNode object) {
                    object.retain(List.of("id", "name", "description", "category", "tags", "price", "currency",
                            "priceType", "priceNotice", "availability", "stockNotice", "selectedOptions", "sourceUrl", "capturedAt"));
                    if ("shopping-recommendation-fast".equals(skillId)) {
                        object.retain(List.of("id", "name", "category", "tags", "price", "availability", "selectedOptions"));
                    }
                }
            }
        } else if ("retrieve_product_knowledge".equals(tool)) {
            for (JsonNode product : data.path("products")) {
                for (JsonNode evidence : product.path("evidence")) {
                    if (evidence instanceof ObjectNode object) object.retain(List.of("id", "productId", "text"));
                }
            }
        } else if ("AI_MEMORY".equals(data.path("source").asText())
                && data.path("memories").isArray() && !data.path("memories").isEmpty()) {
            if ("get_user_profile".equals(tool)) ((ObjectNode) data).remove("interests");
            if ("get_user_behaviors".equals(tool)) ((ObjectNode) data).remove("signals");
        }
        return data;
    }

}
