package com.jd.diviner.ai.rec.odin.demo.app.tools.category;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.Cid2NameCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.CidTopSkuCatalog;
import java.util.List;
import java.util.function.Function;

/**
 * First-screen query parsing plus catalog-gated cid2 helpers for later screens.
 */
public final class CategorySelectionTools {

    private final ObjectMapper mapper;
    private final CidTopSkuCatalog catalog;
    private final Cid2NameCatalog cid2Names;

    public CategorySelectionTools(
            ObjectMapper mapper, CidTopSkuCatalog catalog, Cid2NameCatalog cid2Names) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.cid2Names = cid2Names;
    }

    public SkillTool selectCid1() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putObject("properties").putObject("query")
                .put("type", "string")
                .put("minLength", 5)
                .put("maxLength", 120)
                .put("description", "必须是「产品词｜品牌」两段，用中文或英文竖线分隔。"
                        + "例如 羽毛球拍｜川崎。产品词写具体商品，不要一级/二级类目名。");
        schema.putArray("required").add("query");
        return tool("select_cid1",
                "结合用户画像与近期行为，只返回 1 条召回查询：产品词｜品牌。"
                        + "不要匹配一级或二级类目清单，不要数组、分数、理由或解释。",
                json(schema), this::selectCid1);
    }

    public SkillTool selectCid2() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode item = schema.putObject("properties").putObject("interests")
                .put("type", "array")
                .put("minItems", 1)
                .put("maxItems", 1)
                .putObject("items")
                .put("type", "object")
                .put("additionalProperties", false);
        ObjectNode properties = item.putObject("properties");
        properties.putObject("cid1_name").put("type", "string").put("minLength", 1).put("maxLength", 100);
        properties.putObject("cid2_name").put("type", "string").put("minLength", 1).put("maxLength", 100);
        properties.putObject("interest").put("type", "integer").put("minimum", 0).put("maximum", 100)
                .put("description", "仅依据当前画像与行为预判的初始化兴趣度");
        properties.putObject("confidence").put("type", "integer").put("minimum", 0).put("maximum", 100)
                .put("description", "当前证据对该兴趣预判的置信度，资料不足时应降低");
        item.putArray("required").add("cid1_name").add("cid2_name").add("interest").add("confidence");
        schema.putArray("required").add("interests");
        return tool("select_cid2",
                "为已选的 1 个一级类目从其 cid2_names 中选择 1 个二级类目，并给出 0～100 的初始化兴趣度和置信度。",
                json(schema), this::selectCid2);
    }

    private JsonNode selectCid1(JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || arguments.size() != 1
                || !arguments.path("query").isTextual()) {
            throw new IllegalArgumentException("只需提供 1 个 query，格式为 产品词｜品牌");
        }
        Query query = parseQuery(arguments.path("query").asText());
        ObjectNode result = mapper.createObjectNode()
                .put("source", "QUERY_INTEREST")
                .put("query", query.text())
                .put("product", query.product())
                .put("brand", query.brand())
                .put("cid2_name", query.product());
        result.putArray("cid2_names").add(query.product());
        result.putArray("candidates").addObject()
                .put("query", query.text())
                .put("cid2_name", query.product());
        return result;
    }

    static Query parseQuery(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > 120 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("query 必须为 1～120 个可见字符");
        }
        String normalized = value.replace('｜', '|').replace('丨', '|');
        String[] parts = normalized.split("\\|", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("query 必须是「产品词｜品牌」两段");
        }
        String product = parts[0].strip();
        String brand = parts[1].strip();
        if (product.isEmpty() || brand.isEmpty()
                || product.length() > 40 || brand.length() > 40) {
            throw new IllegalArgumentException("产品词、品牌都不能为空");
        }
        return new Query(product + "|" + brand, product, brand);
    }

    record Query(String text, String product, String brand) {
    }

    private JsonNode selectCid2(JsonNode arguments) {
        JsonNode selected = arguments == null ? null : arguments.get("interests");
        if (arguments == null || !arguments.isObject() || arguments.size() != 1
                || selected == null || !selected.isArray() || selected.size() != 1) {
            throw new IllegalArgumentException("需要提供 1 个 cid2 兴趣");
        }
        JsonNode value = selected.get(0);
        if (!value.isObject() || value.size() != 4
                || !value.path("cid1_name").isTextual() || !value.path("cid2_name").isTextual()
                || !score(value.path("interest")) || !score(value.path("confidence"))) {
            throw new IllegalArgumentException(
                    "兴趣需要 cid1_name、cid2_name、interest 与 confidence");
        }
        String cid1 = resolveName(
                requireName(value.path("cid1_name").asText(), "cid1_name"),
                catalog.intentCid1Names(),
                "一级类目不在可选清单中: ");
        String cid2 = resolveName(
                requireName(value.path("cid2_name").asText(), "cid2_name"),
                catalog.cid2Names(cid1),
                "二级类目不属于所选一级类目: " + cid1 + " / ");
        Cid2NameCatalog.Entry entry = cid2Names.require(cid1, cid2);
        ObjectNode interest = mapper.createObjectNode()
                .put("cid1_name", cid1)
                .put("cid2_name", cid2)
                .put("cid2", entry.cid2())
                .put("interest", value.path("interest").asInt())
                .put("confidence", value.path("confidence").asInt());
        ObjectNode result = mapper.createObjectNode()
                .put("source", "CID_CATALOG")
                .put("cid1_name", cid1)
                .put("cid2_name", cid2)
                .put("cid2", entry.cid2())
                .put("interest", interest.path("interest").asInt())
                .put("confidence", interest.path("confidence").asInt());
        result.putArray("interests").add(interest);
        ArrayNode related = result.putArray("related_cid2");
        for (String name : catalog.cid2Names(cid1)) {
            Cid2NameCatalog.Entry sibling = cid2Names.find(cid1, name);
            if (sibling == null) {
                continue;
            }
            related.addObject()
                    .put("cid1_name", sibling.cid1Name())
                    .put("cid2_name", sibling.cid2Name())
                    .put("cid2", sibling.cid2());
        }
        return result;
    }

    private static boolean score(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt()
                && value.asInt() >= 0 && value.asInt() <= 100;
    }

    private SkillTool tool(String name, String description, String schema, Function<JsonNode, JsonNode> operation) {
        return new SkillTool() {
            public String name() { return name; }
            public String description() { return description; }
            public String inputSchema() { return schema; }
            public String dataMode() { return "LIVE"; }
            public JsonNode execute(JsonNode arguments) { return operation.apply(arguments); }
        };
    }

    private static String requireName(String value, String field) {
        String name = value == null ? "" : value.strip();
        if (name.isEmpty() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " 无效");
        }
        return name;
    }

    private static String resolveName(String requested, List<String> allowed, String errorPrefix) {
        if (allowed.contains(requested)) {
            return requested;
        }
        String comparable = comparableName(requested);
        List<String> matches = allowed.stream()
                .filter(candidate -> comparableName(candidate).equals(comparable))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(errorPrefix + requested);
        }
        return matches.get(0);
    }

    static String comparableName(String name) {
        return name.codePoints()
                .filter(Character::isLetterOrDigit)
                .collect(
                        StringBuilder::new,
                        StringBuilder::appendCodePoint,
                        StringBuilder::append)
                .toString();
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成类目工具 schema", exception);
        }
    }
}
