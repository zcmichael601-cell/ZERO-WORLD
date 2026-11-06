package com.jd.diviner.ai.rec.odin.demo.app.domain.product;

import com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation.FastRecommendationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;

public final class ProductSearchService {

    private final ObjectMapper mapper;
    private final JdProductCatalog catalog;

    public ProductSearchService(ObjectMapper mapper, JdProductCatalog catalog) {
        this.mapper = mapper;
        this.catalog = catalog;
    }

    public JsonNode prepareArguments(JsonNode profile, JsonNode behaviors) {
        var arguments = mapper.createObjectNode();
        arguments.set("interests", mapper.valueToTree(catalog.matchingInterests(FastRecommendationContext.searchText(profile, behaviors))));
        arguments.set("budget", profile.path("budget").isNumber() ? profile.get("budget") : mapper.nullNode());
        arguments.put("limit", 6);
        return arguments;
    }
    public JsonNode search(JsonNode arguments) {
        var interests = validateArguments(arguments, 12);
        var products = catalog.search(interests, arguments.path("budget").isNull() ? null : arguments.path("budget").decimalValue(), arguments.path("limit").asInt());
        var result = mapper.createObjectNode().put("source", "JD_PAGE_SNAPSHOT")
                .put("priceMaxAgeHours", catalog.maxAgeHours())
                .put("notice", "真实京东商品的本地页面快照，非全站搜索或实时报价；库存和成交价需在京东核实。标签仅用于检索，不是商品性能证据。");
        result.set("products", mapper.valueToTree(products));
        result.put("budgetKnown", !arguments.path("budget").isNull());
        if (products.isEmpty()) result.put("emptyReason", "当前目录中无满足标签、预算、价格有效期且未明确缺货的商品；可更新采集目录后重启应用。");
        return result;
    }

    public static java.util.List<String> validateArguments(JsonNode arguments, int maximum) {
        if (arguments == null || !arguments.isObject() || arguments.size() != 3
                || !arguments.path("interests").isArray()
                || arguments.path("interests").size() > 12 || !arguments.has("budget")
                || (!arguments.path("budget").isNull() && (!arguments.path("budget").isNumber()
                || !Double.isFinite(arguments.path("budget").asDouble()) || arguments.path("budget").decimalValue().signum() <= 0))
                || !arguments.path("limit").isIntegralNumber() || !arguments.path("limit").canConvertToInt()
                || arguments.path("limit").asInt() < 1 || arguments.path("limit").asInt() > maximum) {
            throw new IllegalArgumentException("需要 0~12 个兴趣标签、正数或 null 预算及 1~" + maximum + " 的 limit");
        }
        var interests = new ArrayList<String>();
        for (JsonNode interest : arguments.path("interests")) {
            if (!interest.isTextual() || interest.asText().isBlank() || interest.asText().length() > 50
                    || interest.asText().chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("兴趣标签无效");
            interests.add(interest.asText().strip());
        }
        return interests.stream().distinct().toList();
    }
}
