package com.jd.diviner.ai.rec.odin.demo.app.application.jevcid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillBehavior;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillContext;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillDataView;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillExecutionException;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.execution.SkillExecutor;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.execution.WorkflowExecutor;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation.UserIntentPortrait;
import com.jd.diviner.ai.rec.odin.demo.app.tools.user.SessionUserTool;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Keeps all cid recommendation orchestration inside the skill extension.
 * The shared SkillEngine only executes the generic workflow contract.
 */
final class JevCidSkillBehavior extends SkillBehavior {

    private final List<String> simulatedClickSkuIds;
    private final List<String> exposedSkuIds;
    private final List<String> sessionClickSkuIds;
    private final int screenNumber;
    private final boolean nextScreen;

    JevCidSkillBehavior(SkillContext context) {
        super(context);
        simulatedClickSkuIds = textValues(input.path("simulatedClickSkuIds"));
        exposedSkuIds = textValues(input.path("exposedSkuIds"));
        sessionClickSkuIds = sessionClickSkuIds(input.path("sessionEvents"), simulatedClickSkuIds);
        screenNumber = input.path("screenNumber").asInt(1);
        nextScreen = screenNumber > 1;
    }

    @Override
    public SkillExecutor executor() {
        List<WorkflowExecutor.Step> steps = new ArrayList<>();
        steps.add(new WorkflowExecutor.Tool("Skill",
                run -> run.mapper().createObjectNode().put("command", run.skill().id())));
        steps.add(userTool("get_user_profile"));
        steps.add(userTool("get_user_behaviors"));
        if (nextScreen && !exploreOnSkip()) {
            steps.add(new WorkflowExecutor.Tool("recall_cid2", this::recallArguments));
        } else {
            steps.add(new WorkflowExecutor.Decision("select_cid1"));
            steps.add(new WorkflowExecutor.Tool("recall_cid2", this::queryRecallArguments));
        }
        steps.add(new WorkflowExecutor.Complete(run -> run.mapper().createObjectNode()));
        return new WorkflowExecutor(steps);
    }

    private WorkflowExecutor.Tool userTool(String name) {
        return new WorkflowExecutor.Tool(name,
                run -> run.mapper().createObjectNode().put("username", run.input().path("username").asText()));
    }

    private Set<String> exposureCache() {
        LinkedHashSet<String> ids = new LinkedHashSet<>(exposedSkuIds);
        for (JsonNode event : input.path("sessionEvents")) {
            if (event.path("skuId").isTextual()) {
                ids.add(event.path("skuId").asText());
            }
        }
        return Set.copyOf(ids);
    }

    private JsonNode queryRecallArguments(SkillContext run) {
        JsonNode selected = toolResults.get("select_cid1");
        ObjectNode arguments = mapper.createObjectNode();
        if (selected != null && selected.path("query").isTextual()) {
            arguments.put("query", selected.path("query").asText());
        }
        if (selected != null && selected.path("brand").isTextual()
                && !selected.path("brand").asText().isBlank()) {
            arguments.put("brand", selected.path("brand").asText());
        }
        return arguments;
    }

    private JsonNode recallArguments(SkillContext run) {
        ObjectNode arguments = mapper.createObjectNode();
        JsonNode click = latestThisScreenClick();
        String cid2Name = click != null ? text(click, "cid2Name", "cid2_name") : "";
        if (cid2Name.isBlank()) {
            cid2Name = optionalCid2Name();
        }
        if (!cid2Name.isBlank()) {
            arguments.put("cid2_name", cid2Name);
        }
        if (click != null) {
            if (click.path("cid3").isIntegralNumber() && click.path("cid3").canConvertToInt()
                    && click.path("cid3").asInt() > 0) {
                arguments.put("cid3", click.path("cid3").asInt());
            }
            if (click.path("brand").isTextual() && !click.path("brand").asText().isBlank()) {
                arguments.put("brand", click.path("brand").asText());
            }
        }
        return arguments;
    }

    @Override
    public SkillTool resolveTool(String name) {
        SkillTool tool = registry.require(name);
        if (Set.of("get_user_profile", "get_user_behaviors").contains(name)) {
            return new SessionUserTool(mapper, tool, input.path("username").asText());
        }
        if ("select_cid1".equals(name) && exploreOnSkip()) {
            return relatedQueryTool(tool);
        }
        if ("recall_cid2".equals(name)) {
            JsonNode currentInterests = input.path("currentInterests").isArray()
                    ? input.path("currentInterests")
                    : input.path("initialInterests");
            return new CategoryRecallTool(
                    mapper,
                    registry.require("select_cid2"),
                    tool,
                    !nextScreen,
                    toolResults.get("select_cid1"),
                    simulatedClickSkuIds,
                    currentInterests,
                    input.path("relatedCid2"),
                    initialEvidenceCount(),
                    nextScreen ? screenNumber - 1 : 0,
                    exposureCache());
        }
        return tool;
    }

    @Override
    public JsonNode toolData(String name, JsonNode result) {
        if ("get_user_behaviors".equals(name)) {
            return UserIntentPortrait.countedBehaviors(result, mapper);
        }
        return SkillDataView.businessData(result);
    }

    @Override
    public void afterTool(String name, JsonNode result) {
        if ("recall_cid2".equals(name) && !result.path("products").isArray()) {
            throw new SkillExecutionException(name + " 未返回 products 数组");
        }
    }

    private JsonNode requireResult(String name) {
        JsonNode result = toolResults.get(name);
        if (result == null) {
            throw new SkillExecutionException("类目工作流顺序异常，缺少 " + name);
        }
        return result;
    }

    private int initialEvidenceCount() {
        int count = 0;
        for (String name : List.of("get_user_profile", "get_user_behaviors")) {
            JsonNode result = toolResults.get(name);
            if (result == null) {
                continue;
            }
            JsonNode memories = result.path("memories");
            if (memories.isArray()) {
                count += memories.size();
            } else if (result.path("counts").isObject() && !result.path("counts").isEmpty()) {
                count += result.path("counts").size();
            }
        }
        return count;
    }

    private static List<String> textValues(JsonNode values) {
        if (!values.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.size());
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static List<String> sessionClickSkuIds(JsonNode events, List<String> currentClicks) {
        Set<String> recent = new LinkedHashSet<>();
        for (String skuId : currentClicks) {
            if (recent.size() == 10) {
                break;
            }
            recent.add(skuId);
        }
        if (events.isArray()) {
            for (int index = events.size() - 1; index >= 0 && recent.size() < 10; index--) {
                JsonNode event = events.get(index);
                if (event.path("clicked").asBoolean(false)
                        && event.path("skuId").isTextual()) {
                    recent.add(event.path("skuId").asText());
                }
            }
        }
        return List.copyOf(recent);
    }

    private JsonNode latestThisScreenClick() {
        if (simulatedClickSkuIds.isEmpty()) {
            return null;
        }
        Set<String> clicks = Set.copyOf(simulatedClickSkuIds);
        JsonNode events = input.path("sessionEvents");
        if (!events.isArray()) {
            return null;
        }
        for (int index = events.size() - 1; index >= 0; index--) {
            JsonNode event = events.get(index);
            if (event.path("clicked").asBoolean(false)
                    && clicks.contains(event.path("skuId").asText())) {
                return event;
            }
        }
        return null;
    }

    /**
     * Next screens follow the phone feed, not the left bypass panel.
     * This screen's clicks seed i2i; a skip/no-click loads novel categories.
     */
    private boolean exploreOnSkip() {
        return nextScreen && simulatedClickSkuIds.isEmpty();
    }

    private String currentProductWord() {
        JsonNode interests = input.path("currentInterests").isArray() && !input.path("currentInterests").isEmpty()
                ? input.path("currentInterests")
                : input.path("initialInterests");
        if (!interests.isArray() || interests.isEmpty()) {
            return "";
        }
        JsonNode interest = interests.get(0);
        if (interest.path("query").isTextual() && interest.path("query").asText().contains("|")) {
            return interest.path("query").asText().split("\\|", 2)[0].strip();
        }
        return text(interest, "cid2Name", "cid2_name");
    }

    private String optionalCid2Name() {
        JsonNode interests = input.path("currentInterests").isArray() && !input.path("currentInterests").isEmpty()
                ? input.path("currentInterests")
                : input.path("initialInterests");
        if (!interests.isArray() || interests.isEmpty()) {
            return "";
        }
        return text(interests.get(0), "cid2Name", "cid2_name");
    }

    private static String text(JsonNode value, String... fields) {
        for (String field : fields) {
            if (value.path(field).isTextual() && !value.path(field).asText().isBlank()) {
                return value.path(field).asText().strip();
            }
        }
        return "";
    }

    @Override
    public List<Message> initialHistory() {
        String system;
        if (!nextScreen) {
            system = "首屏只返回「产品词｜品牌」，不要匹配一级或二级类目。";
        } else if (exploreOnSkip()) {
            system = relatedQueryPrompt();
        } else {
            system = "后续屏由本屏 App 动作决定：有点击走 i2i。不使用旁路兴趣画像。";
        }
        return new ArrayList<>(List.of(
                new SystemMessage(system),
                new UserMessage("执行 Skill " + skill.id() + "。用户输入 JSON：" + input)));
    }

    @Override
    public JsonNode finish(ObjectNode ignoredLocalOutput) {
        JsonNode recall = requireResult("recall_cid2");
        boolean i2i = "DIVINER_I2I".equals(recall.path("source").asText());
        String source = i2i ? "DIVINER_I2I" : "DIVINER_CID2";
        String categoryPath = i2i
                ? "模拟点击/相似商品"
                : recall.path("query").isTextual() && !recall.path("query").asText().isBlank()
                ? recall.path("query").asText()
                : recall.path("cid1_name").asText("").isBlank()
                ? categoryRecallLabel(recall)
                : recall.path("cid1_name").asText() + "/" + recall.path("cid2_name").asText();
        ArrayNode products = mapper.createArrayNode();
        Set<String> seenSkuIds = new LinkedHashSet<>();
        for (JsonNode item : recall.path("products")) {
            addProduct(products, seenSkuIds, item, recall, source, categoryPath);
        }
        if (products.isEmpty()) {
            throw new SkillExecutionException((i2i ? "i2i" : "cid2 类目") + "召回未返回有效商品卡片");
        }

        ObjectNode output = mapper.createObjectNode();
        output.set("products", products);
        ArrayNode initialInterests = output.putArray("initialInterests");
        if (!nextScreen) {
            for (JsonNode interest : recall.path("interests")) {
                ObjectNode copied = initialInterests.addObject()
                        .put("cid2Name", interest.path("cid2_name").asText())
                        .put("cid2", interest.path("cid2").asInt())
                        .put("interest", interest.path("interest").asInt())
                        .put("confidence", interest.path("confidence").asInt())
                        .put("source", "MODEL_PROFILE_PREDICTION");
                if (interest.path("cid1_name").isTextual() && !interest.path("cid1_name").asText().isBlank()) {
                    copied.put("cid1Name", interest.path("cid1_name").asText());
                }
                if (interest.path("brand").isTextual() && !interest.path("brand").asText().isBlank()) {
                    copied.put("brand", interest.path("brand").asText());
                }
                if (recall.path("query").isTextual() && !recall.path("query").asText().isBlank()) {
                    copied.put("query", recall.path("query").asText());
                }
            }
            output.set("currentInterests", initialInterests.deepCopy());
        } else {
            output.set("currentInterests", recall.path("currentInterests").deepCopy());
        }
        output.set("relatedCid2", recall.path("relatedCid2").isArray()
                ? recall.path("relatedCid2").deepCopy()
                : relatedCid2(recall.path("related_cid2")));
        ArrayNode categories = output.putArray("categories");
        categories.addObject()
                .put("cid1_name", recall.path("cid1_name").asText())
                .put("cid2_name", recall.path("cid2_name").asText())
                .put("cid2", recall.path("cid2").asInt());
        output.set("seedSkuIds", recall.path("seedSkuIds").isArray()
                ? recall.path("seedSkuIds").deepCopy()
                : mapper.createArrayNode());
        output.set("categoryRecallSkuIds", recall.path("skuIds").isArray()
                ? recall.path("skuIds").deepCopy()
                : mapper.createArrayNode());
        output.set("recallConstraints", recall.path("constraints").isObject()
                ? recall.path("constraints").deepCopy()
                : mapper.createObjectNode());
        output.put("categoryRecallCount", i2i ? 0 : recall.path("products").size());
        output.put("i2iRecallCount", i2i ? recall.path("products").size() : 0);
        ObjectNode interaction = output.putObject("interaction")
                .put("screenNumber", screenNumber)
                .put("mode", interactionMode(i2i))
                .put("interestBaselineScreen",
                        nextScreen ? recall.path("interestBaselineScreen").asInt(screenNumber - 1) : 0);
        interaction.set("simulatedClickSkuIds", mapper.valueToTree(simulatedClickSkuIds));
        interaction.set("exposedSkuIds", mapper.valueToTree(exposedSkuIds));
        JsonNode profileResult = toolResults.get("get_user_profile");
        if (profileResult != null && SkillDataView.businessData(profileResult) instanceof ObjectNode profile) {
            JsonNode behaviors = toolResults.get("get_user_behaviors");
            if (behaviors != null) {
                profile.set("behaviors", UserIntentPortrait.countedBehaviors(behaviors, mapper).path("counts"));
            }
            output.set("profile", profile);
        }
        return output;
    }

    private void addProduct(
            ArrayNode products,
            Set<String> seenSkuIds,
            JsonNode item,
            JsonNode selection,
            String source,
            String categoryPath) {
        String skuId = item.path("skuId").asText();
        if (skuId.isBlank() || item.path("name").asText().isBlank() || !seenSkuIds.add(skuId)) {
            return;
        }
        String cid1Name = text(item, "cid1Name", "cid1_name");
        String cid2Name = text(item, "cid2Name", "cid2_name");
        int itemCid2 = item.path("cid2").canConvertToInt() ? item.path("cid2").asInt() : 0;
        int selectedCid2 = selection != null && selection.path("cid2").canConvertToInt()
                ? selection.path("cid2").asInt() : 0;
        if (cid2Name.isBlank() && selection != null && !selection.path("cid2_name").asText("").isBlank()
                && (itemCid2 <= 0 || selectedCid2 <= 0 || itemCid2 == selectedCid2)) {
            cid1Name = selection.path("cid1_name").asText();
            cid2Name = selection.path("cid2_name").asText();
        }
        String category = "DIVINER_I2I".equals(source)
                ? categoryPath
                : !cid1Name.isBlank() && !cid2Name.isBlank()
                ? cid1Name + "/" + cid2Name
                : categoryPath;
        ObjectNode product = products.addObject()
                .put("id", skuId)
                .put("skuId", skuId)
                .put("name", item.path("name").asText())
                .put("brand", item.path("brand").asText())
                .put("description", item.path("brand").asText())
                .put("category", category)
                .put("icon", "🛍️")
                .put("image", item.path("image").asText())
                .put("source", source)
                .put("recallBranch", "DIVINER_I2I".equals(source) ? "I2I" : "CATEGORY")
                .put("availability", "UNKNOWN")
                .put("priceType", "JD_PRICE")
                .put("currency", "CNY")
                .put("priceNotice", "Diviner 返回的京东价，成交价以京东商详为准");
        copyInteger(product, item, "cid1");
        copyInteger(product, item, "cid2");
        copyInteger(product, item, "cid3");
        if (!cid2Name.isBlank()) {
            product.put("cid1Name", cid1Name);
            product.put("cid2Name", cid2Name);
        }
        if (item.path("price").isNumber() && item.path("price").asDouble() > 0) {
            product.put("price", item.path("price").asDouble());
        } else {
            product.putNull("price");
        }
    }

    private ArrayNode relatedCid2(JsonNode related) {
        ArrayNode values = mapper.createArrayNode();
        if (!related.isArray()) {
            return values;
        }
        for (JsonNode value : related) {
            values.addObject()
                    .put("cid1Name", value.path("cid1_name").asText())
                    .put("cid2Name", value.path("cid2_name").asText())
                    .put("cid2", value.path("cid2").asInt());
        }
        return values;
    }

    private static String categoryRecallLabel(JsonNode recall) {
        JsonNode constraints = recall.path("constraints");
        StringBuilder label = new StringBuilder("类目召回 · cid2 ")
                .append(constraints.path("cid2").asInt());
        if (constraints.path("cid3").isIntegralNumber()) {
            label.append(" · cid3 ").append(constraints.path("cid3").asInt());
        }
        if (constraints.path("brand").isTextual()) {
            label.append(" · ").append(constraints.path("brand").asText());
        }
        return label.toString();
    }

    private static void copyInteger(ObjectNode target, JsonNode source, String field) {
        if (source.path(field).isIntegralNumber() && source.path(field).canConvertToInt()) {
            target.put(field, source.path(field).asInt());
        }
    }

    private String interactionMode(boolean i2i) {
        if (!nextScreen) {
            return "CATEGORY_INTENT";
        }
        String explore = toolResults.getOrDefault("recall_cid2", mapper.missingNode())
                .path("explore").asText();
        if ("RELATED_QUERY".equals(explore) || "NOVEL_CATEGORY".equals(explore) || "NOVEL_PHONE".equals(explore)) {
            return "CATEGORY_NOVEL";
        }
        return i2i ? "CATEGORY_AND_CLICK_I2I" : "CATEGORY_ONLY_NO_CLICK";
    }

    @Override
    public String toolLabel(String name) {
        if ("select_cid1".equals(name)) {
            return "决策产品词｜品牌";
        }
        if ("recall_cid2".equals(name)) {
            if (exploreOnSkip()) {
                return "按相关产品词召回";
            }
            return simulatedClickSkuIds.isEmpty() ? "按类目参数召回商品" : "按本屏点击种子做 i2i 召回";
        }
        return super.toolLabel(name);
    }

    @Override
    public String toolError(String name, RuntimeException failure) {
        return "recall_cid2".equals(name) ? "商品召回失败" : super.toolError(name, failure);
    }

    @Override
    public Object[] diagnosticFields() {
        JsonNode recall = toolResults.get("recall_cid2");
        boolean i2i = recall != null && "DIVINER_I2I".equals(recall.path("source").asText());
        return new Object[] {
                "screenNumber", screenNumber,
                "simulatedClickCount", simulatedClickSkuIds.size(),
                "sessionClickSeedCount", sessionClickSkuIds.size(),
                "exposureCount", exposedSkuIds.size(),
                "cid2RecallCount", i2i || recall == null ? 0 : recall.path("products").size(),
                "i2iProductCount", i2i ? recall.path("products").size() : 0
        };
    }

    private SkillTool relatedQueryTool(SkillTool tool) {
        List<String> used = usedQueries();
        String description = relatedQueryPrompt();
        return new SkillTool() {
            @Override
            public String name() {
                return tool.name();
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String inputSchema() {
                return tool.inputSchema();
            }

            @Override
            public String dataMode() {
                return tool.dataMode();
            }

            @Override
            public JsonNode execute(JsonNode arguments) {
                JsonNode result = tool.execute(arguments);
                String query = normalizeQuery(result.path("query").asText(""));
                String product = productWord(query);
                Set<String> usedProducts = used.stream()
                        .map(JevCidSkillBehavior::productWord)
                        .filter(word -> !word.isBlank())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                if (!product.isBlank() && usedProducts.contains(product)) {
                    throw new IllegalArgumentException("产品词已用过，请换一个相关产品词，品牌可以沿用");
                }
                return result;
            }
        };
    }

    private String relatedQueryPrompt() {
        String product = currentProductWord();
        List<String> used = usedQueries();
        String usedText = used.isEmpty() ? "无" : String.join("、", used);
        String usedProducts = used.stream()
                .map(JevCidSkillBehavior::productWord)
                .filter(word -> !word.isBlank())
                .distinct()
                .collect(Collectors.joining("、"));
        return "本屏无点击，这是第 " + screenNumber + " 屏。"
                + "锚定首屏产品词「" + (product.isBlank() ? "未知" : product) + "」。"
                + "已经用过的查询：" + usedText + "。"
                + "已经用过的产品词：" + (usedProducts.isBlank() ? "无" : usedProducts) + "。"
                + "这一屏必须换一个相关产品词，品牌可以沿用，不要重复已用过的产品词，不要写成类目名。";
    }

    private List<String> usedQueries() {
        LinkedHashSet<String> used = new LinkedHashSet<>();
        addQuery(used, input.path("usedQueries"));
        addInterestQuery(used, input.path("initialInterests"));
        addInterestQuery(used, input.path("currentInterests"));
        for (JsonNode event : input.path("sessionEvents")) {
            addQuery(used, event.path("category"));
        }
        return List.copyOf(used);
    }

    private static void addInterestQuery(Set<String> used, JsonNode interests) {
        if (!interests.isArray()) {
            return;
        }
        interests.forEach(interest -> addQuery(used, interest.path("query")));
    }

    private static void addQuery(Set<String> used, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            value.forEach(item -> addQuery(used, item));
            return;
        }
        if (!value.isTextual()) {
            return;
        }
        String query = normalizeQuery(value.asText());
        if (query.contains("|") && !query.startsWith("模拟点击")) {
            used.add(query);
        }
    }

    private static String normalizeQuery(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.strip().replace('｜', '|').replace('丨', '|');
    }

    private static String productWord(String query) {
        String normalized = normalizeQuery(query);
        if (normalized.isBlank()) {
            return "";
        }
        int split = normalized.indexOf('|');
        return (split < 0 ? normalized : normalized.substring(0, split)).strip();
    }
}
