package com.jd.diviner.ai.rec.odin.demo.app.application.jevcid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workflow adapter around recall_cid2. The model chooses cid1, then a cid2 under it;
 * this wrapper maps them and injects session click SKUs.
 */
final class CategoryRecallTool implements SkillTool {

    private static final Set<String> FIRST_FIELDS = Set.of("cid2_name", "brand");
    private static final Set<String> NEXT_FIELDS = Set.of("cid2_name", "cid3", "brand");
    private static final int QUERY_CID2 = 1;

    private final ObjectMapper mapper;
    private final SkillTool selectCid2;
    private final SkillTool recall;
    private final boolean firstScreen;
    private final JsonNode chosenCid1;
    private final List<String> skus;
    private final Map<String, Candidate> related;
    private final Candidate previous;
    private final int evidenceCount;
    private final int interestBaselineScreen;
    private final Set<String> exposedSkuIds;

    CategoryRecallTool(
            ObjectMapper mapper,
            SkillTool selectCid2,
            SkillTool recall,
            boolean firstScreen,
            JsonNode chosenCid1,
            List<String> skus,
            JsonNode currentInterests,
            JsonNode relatedCid2,
            int evidenceCount,
            int interestBaselineScreen,
            Set<String> exposedSkuIds) {
        this.mapper = mapper;
        this.selectCid2 = selectCid2;
        this.recall = recall;
        this.firstScreen = firstScreen;
        this.chosenCid1 = chosenCid1;
        this.skus = skus == null ? List.of() : List.copyOf(skus);
        this.previous = previous(currentInterests);
        this.related = relatedCandidates(currentInterests, relatedCid2);
        this.evidenceCount = Math.max(0, evidenceCount);
        this.interestBaselineScreen = interestBaselineScreen;
        this.exposedSkuIds = exposedSkuIds == null ? Set.of() : Set.copyOf(exposedSkuIds);
        if (!firstScreen && related.isEmpty() && this.skus.isEmpty()
                && (chosenCid1 == null || !chosenCid1.path("query").isTextual())) {
            throw new IllegalArgumentException("后续屏缺少可更新的 cid2 兴趣候选");
        }
    }

    @Override
    public String name() {
        return recall.name();
    }

    @Override
    public String description() {
        if (chosenCid1 != null && chosenCid1.path("query").isTextual()) {
            return firstScreen
                    ? "首屏已由模型给出「产品词｜品牌」，按该查询文本走 675，不再匹配一二级类目。"
                    : "按模型给出的相关产品词｜品牌走 675，只召回和该产品词相关的商品。";
        }
        StringBuilder allowed = new StringBuilder();
        related.values().forEach(candidate -> {
            if (!allowed.isEmpty()) {
                allowed.append("；");
            }
            allowed.append(candidate.cid2Name());
        });
        return "后续屏以本屏 App 动作为准：有点击 SKU 时 i2i。不使用旁路兴趣画像。"
                + "可选 cid2：" + allowed;
    }

    @Override
    public String inputSchema() {
        ObjectNode schema = mapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        if (firstScreen) {
            properties.putObject("query")
                    .put("type", "string")
                    .put("description", "沿用 select_cid1 的产品词｜品牌，不要改写成类目名");
        } else {
            ObjectNode cid2 = properties.putObject("cid2_name")
                    .put("type", "string")
                    .put("description", "与当前 cid2 相同，或同一一级类目下的相关二级类目");
            ArrayNode allowed = cid2.putArray("enum");
            related.keySet().forEach(allowed::add);
            properties.putObject("cid3")
                    .put("type", "integer")
                    .put("minimum", 1)
                    .put("maximum", 999999999);
            properties.putObject("brand")
                    .put("type", "string")
                    .put("minLength", 1)
                    .put("maxLength", 100);
            schema.putArray("required").add("cid2_name");
        }
        return schema.toString();
    }

    @Override
    public String dataMode() {
        return recall.dataMode();
    }

    @Override
    public JsonNode execute(JsonNode arguments) {
        if (chosenCid1 != null && chosenCid1.path("query").isTextual()) {
            return recallByQuery(chosenCid1);
        }
        if (arguments != null && arguments.path("explore").isArray()
                && !arguments.path("explore").isEmpty()) {
            return explore(arguments.path("explore"));
        }
        if (!skus.isEmpty()) {
            return recallByClick(arguments);
        }
        Selection selection = selectNext(arguments);
        return stampRecall(recall.execute(recallArguments(selection, arguments, false)), selection, arguments);
    }

    private JsonNode recallByQuery(JsonNode selected) {
        if (selected == null || !selected.path("query").isTextual()
                || selected.path("query").asText().isBlank()) {
            throw new IllegalArgumentException("缺少产品词｜品牌查询");
        }
        String query = selected.path("query").asText().strip();
        String product = firstText(selected, "product", "cid2_name");
        if (product.isBlank()) {
            product = query.split("\\|", 2)[0];
        }
        String brand = firstText(selected, "brand");
        ObjectNode recallArguments = mapper.createObjectNode().put("query", query);
        if (!brand.isBlank()) {
            recallArguments.put("brand", brand);
        }
        attachExposure(recallArguments);
        ArrayNode relatedNodes = mapper.createArrayNode();
        relatedNodes.addObject()
                .put("cid1_name", "")
                .put("cid2_name", product)
                .put("cid2", QUERY_CID2);
        Selection selection = new Selection(
                "",
                product,
                QUERY_CID2,
                initialInterest(),
                initialConfidence(),
                relatedNodes);
        JsonNode output = stampRecall(recall.execute(recallArguments), selection, recallArguments);
        if (output instanceof ObjectNode node) {
            node.put("query", query);
            node.put("cid2_name", product);
            if (!firstScreen) {
                node.put("explore", "RELATED_QUERY");
            }
            if (node.path("currentInterests").isArray() && !node.path("currentInterests").isEmpty()
                    && node.path("currentInterests").get(0) instanceof ObjectNode current) {
                current.put("query", query);
            }
        }
        return output;
    }

    private JsonNode recallByClick(JsonNode arguments) {
        Selection selection = clickSelection(arguments);
        return stampRecall(recall.execute(recallArguments(selection, arguments, true)), selection, arguments);
    }

    private ObjectNode recallArguments(Selection selection, JsonNode arguments, boolean i2i) {
        ObjectNode recallArguments = mapper.createObjectNode();
        if (i2i) {
            ArrayNode seeds = recallArguments.putArray("skus");
            skus.forEach(seeds::add);
        }
        if (selection.cid2() > 0) {
            recallArguments.put("cid2", selection.cid2());
            if (!selection.cid2Name().isBlank()) {
                recallArguments.put("cid2_name", selection.cid2Name());
            }
        }
        if (arguments != null && arguments.has("cid3")) {
            recallArguments.set("cid3", arguments.path("cid3").deepCopy());
        }
        if (arguments != null && arguments.has("brand")) {
            recallArguments.set("brand", arguments.path("brand").deepCopy());
        }
        attachExposure(recallArguments);
        return recallArguments;
    }

    private JsonNode stampRecall(JsonNode recalled, Selection selection, JsonNode arguments) {
        if (!(recalled instanceof ObjectNode result)) {
            throw new IllegalArgumentException("recall_cid2 未返回对象结果");
        }
        ObjectNode output = result.deepCopy();
        output.put("cid1_name", selection.cid1Name())
                .put("cid2_name", selection.cid2Name())
                .put("cid2", selection.cid2())
                .put("interest", selection.interest())
                .put("confidence", selection.confidence());
        ObjectNode interest = output.putArray("interests").addObject()
                .put("cid2_name", selection.cid2Name())
                .put("cid2", selection.cid2())
                .put("interest", selection.interest())
                .put("confidence", selection.confidence());
        if (!selection.cid1Name().isBlank()) {
            interest.put("cid1_name", selection.cid1Name());
        }
        ObjectNode current = output.putArray("currentInterests").addObject()
                .put("cid2Name", selection.cid2Name())
                .put("cid2", selection.cid2())
                .put("interest", selection.interest())
                .put("confidence", selection.confidence())
                .put("source", firstScreen ? "MODEL_PROFILE_PREDICTION" : "WORKFLOW_SESSION");
        if (!selection.cid1Name().isBlank()) {
            current.put("cid1Name", selection.cid1Name());
        }
        if (arguments != null && arguments.path("brand").isTextual()
                && !arguments.path("brand").asText().isBlank()) {
            String brand = arguments.path("brand").asText();
            interest.put("brand", brand);
            current.put("brand", brand);
        }
        output.set("related_cid2", selection.related().deepCopy());
        output.set("relatedCid2", relatedOutput(selection.related()));
        if (!firstScreen) {
            output.put("interestBaselineScreen", interestBaselineScreen);
        }
        return output;
    }

    private Selection clickSelection(JsonNode arguments) {
        Candidate selected = null;
        if (arguments != null && arguments.path("cid2_name").isTextual()) {
            selected = related.get(arguments.path("cid2_name").asText());
            if (selected == null) {
                String comparable = comparable(arguments.path("cid2_name").asText());
                selected = related.values().stream()
                        .filter(candidate -> comparable(candidate.cid2Name()).equals(comparable))
                        .findFirst()
                        .orElse(null);
            }
        }
        if (selected == null) {
            selected = previous;
        }
        if (selected == null) {
            return new Selection("", "", 0, 55, 25, mapper.createArrayNode());
        }
        ArrayNode relatedNodes = mapper.createArrayNode();
        related.values().forEach(candidate -> relatedNodes.addObject()
                .put("cid1_name", candidate.cid1Name())
                .put("cid2_name", candidate.cid2Name())
                .put("cid2", candidate.cid2()));
        return new Selection(
                selected.cid1Name(),
                selected.cid2Name(),
                selected.cid2(),
                interestFor(selected),
                confidenceFor(selected),
                relatedNodes);
    }

    private void attachExposure(ObjectNode arguments) {
        if (exposedSkuIds.isEmpty()) {
            return;
        }
        ArrayNode ids = arguments.putArray("exposedSkuIds");
        exposedSkuIds.forEach(ids::add);
    }

    private JsonNode explore(JsonNode categories) {
        ArrayNode products = mapper.createArrayNode();
        ArrayNode skuIds = mapper.createArrayNode();
        ArrayNode related = mapper.createArrayNode();
        JsonNode lead = categories.get(0);
        int kept = 0;
        for (JsonNode category : categories) {
            int cid2 = category.path("cid2").asInt();
            if (cid2 <= 0 || category.path("cid2_name").asText("").isBlank()) {
                throw new IllegalArgumentException("探索类目需要 cid2 和 cid2_name");
            }
            ObjectNode recallArguments = mapper.createObjectNode()
                    .put("cid2", cid2)
                    .put("cid2_name", category.path("cid2_name").asText());
            attachExposure(recallArguments);
            JsonNode recalled = recall.execute(recallArguments);
            related.addObject()
                    .put("cid1_name", category.path("cid1_name").asText())
                    .put("cid2_name", category.path("cid2_name").asText())
                    .put("cid2", cid2);
            int taken = 0;
            for (JsonNode item : recalled.path("products")) {
                if (taken == 6 || kept == 18) {
                    break;
                }
                if (!(item instanceof ObjectNode product)) {
                    continue;
                }
                ObjectNode copy = product.deepCopy();
                copy.put("cid1Name", category.path("cid1_name").asText());
                copy.put("cid2Name", category.path("cid2_name").asText());
                products.add(copy);
                skuIds.add(copy.path("skuId").asText());
                taken++;
                kept++;
            }
        }
        ObjectNode output = mapper.createObjectNode();
        output.put("source", "DIVINER_CID2");
        output.put("explore", "NOVEL_CATEGORY");
        output.set("products", products);
        output.set("skuIds", skuIds);
        output.put("count", products.size());
        output.put("cid1_name", lead.path("cid1_name").asText());
        output.put("cid2_name", lead.path("cid2_name").asText());
        output.put("cid2", lead.path("cid2").asInt());
        output.put("interest", 62);
        output.put("confidence", 40);
        output.putArray("seedSkuIds");
        output.putObject("constraints").put("cid2", lead.path("cid2").asInt());
        output.putArray("interests").addObject()
                .put("cid1_name", lead.path("cid1_name").asText())
                .put("cid2_name", lead.path("cid2_name").asText())
                .put("cid2", lead.path("cid2").asInt())
                .put("interest", 62)
                .put("confidence", 40);
        output.putArray("currentInterests").addObject()
                .put("cid1Name", lead.path("cid1_name").asText())
                .put("cid2Name", lead.path("cid2_name").asText())
                .put("cid2", lead.path("cid2").asInt())
                .put("interest", 62)
                .put("confidence", 40)
                .put("source", "WORKFLOW_SESSION");
        output.set("related_cid2", related);
        output.set("relatedCid2", relatedOutput(related));
        output.put("interestBaselineScreen", interestBaselineScreen);
        return output;
    }

    private Selection selectFirst(JsonNode arguments) {
        if (chosenCid1 == null || !chosenCid1.path("cid1_name").isTextual()
                || !chosenCid1.path("cid2_names").isArray() || chosenCid1.path("cid2_names").isEmpty()) {
            throw new IllegalArgumentException("选择二级类目前必须先完成一级类目");
        }
        requireFields(arguments, FIRST_FIELDS, "必须从该一级类目清单选 cid2_name，并给出 brand（品牌或型号）");
        String cid1Name = chosenCid1.path("cid1_name").asText();
        String cid2Name = arguments.path("cid2_name").asText();
        if (!contains(chosenCid1.path("cid2_names"), cid2Name)) {
            throw new IllegalArgumentException("cid2_name 必须属于所选一级类目");
        }
        ObjectNode delegated = mapper.createObjectNode();
        delegated.putArray("interests").addObject()
                .put("cid1_name", cid1Name)
                .put("cid2_name", cid2Name)
                .put("interest", initialInterest())
                .put("confidence", initialConfidence());
        JsonNode selected = selectCid2.execute(delegated);
        return new Selection(
                selected.path("cid1_name").asText(),
                selected.path("cid2_name").asText(),
                selected.path("cid2").asInt(),
                selected.path("interest").asInt(),
                selected.path("confidence").asInt(),
                selected.path("related_cid2"));
    }

    private Selection selectNext(JsonNode arguments) {
        requireFields(arguments, NEXT_FIELDS, "只需提供 cid2_name，以及可选 cid3、brand");
        Candidate selected = related.get(arguments.path("cid2_name").asText());
        if (selected == null) {
            selected = related.values().stream()
                    .filter(candidate -> comparable(candidate.cid2Name())
                            .equals(comparable(arguments.path("cid2_name").asText())))
                    .findFirst()
                    .orElse(null);
        }
        if (selected == null) {
            throw new IllegalArgumentException("cid2_name 必须来自当前相关候选");
        }
        ArrayNode relatedNodes = mapper.createArrayNode();
        related.values().forEach(candidate -> relatedNodes.addObject()
                .put("cid1_name", candidate.cid1Name())
                .put("cid2_name", candidate.cid2Name())
                .put("cid2", candidate.cid2()));
        return new Selection(
                selected.cid1Name(),
                selected.cid2Name(),
                selected.cid2(),
                interestFor(selected),
                confidenceFor(selected),
                relatedNodes);
    }

    private void requireFields(JsonNode arguments, Set<String> allowed, String error) {
        if (arguments == null || !arguments.isObject()
                || arguments.size() < 1 || arguments.size() > allowed.size()) {
            throw new IllegalArgumentException(error);
        }
        var fields = arguments.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                throw new IllegalArgumentException(error);
            }
        }
        if (!arguments.path("cid2_name").isTextual()
                || arguments.path("cid2_name").asText().isBlank()) {
            throw new IllegalArgumentException("必须填写 1 个 cid2_name");
        }
        if (allowed.contains("brand") && firstScreen) {
            if (!arguments.path("brand").isTextual()
                    || arguments.path("brand").asText().isBlank()
                    || arguments.path("brand").asText().length() > 100
                    || arguments.path("brand").asText().chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("必须填写 brand：完整品牌或具体型号");
            }
        } else if (arguments.has("brand") && (!arguments.path("brand").isTextual()
                || arguments.path("brand").asText().isBlank()
                || arguments.path("brand").asText().length() > 100
                || arguments.path("brand").asText().chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("brand 必须为 1～100 个可见字符");
        }
    }

    private int initialInterest() {
        return evidenceCount == 0 ? 50 : Math.min(85, 55 + evidenceCount * 5);
    }

    private int initialConfidence() {
        return evidenceCount == 0 ? 10 : Math.min(80, 20 + evidenceCount * 5);
    }

    private int interestFor(Candidate selected) {
        if (previous != null && previous.cid2() == selected.cid2()) {
            return previous.interest();
        }
        return previous == null ? 55 : Math.max(10, previous.interest() - 8);
    }

    private int confidenceFor(Candidate selected) {
        if (previous != null && previous.cid2() == selected.cid2()) {
            return previous.confidence();
        }
        return previous == null ? 25 : Math.max(5, previous.confidence() - 8);
    }

    private ArrayNode relatedOutput(JsonNode relatedNodes) {
        ArrayNode values = mapper.createArrayNode();
        if (!relatedNodes.isArray()) {
            return values;
        }
        for (JsonNode value : relatedNodes) {
            ObjectNode related = values.addObject()
                    .put("cid2Name", value.path("cid2_name").asText())
                    .put("cid2", value.path("cid2").asInt());
            if (value.path("cid1_name").isTextual() && !value.path("cid1_name").asText().isBlank()) {
                related.put("cid1Name", value.path("cid1_name").asText());
            }
        }
        return values;
    }

    private static Candidate previous(JsonNode currentInterests) {
        if (!currentInterests.isArray() || currentInterests.isEmpty()) {
            return null;
        }
        return candidate(currentInterests.get(0));
    }

    private static Map<String, Candidate> relatedCandidates(JsonNode currentInterests, JsonNode relatedCid2) {
        Map<String, Candidate> result = new LinkedHashMap<>();
        addAll(result, relatedCid2);
        addAll(result, currentInterests);
        return Map.copyOf(result);
    }

    private static void addAll(Map<String, Candidate> candidates, JsonNode values) {
        if (!values.isArray()) {
            return;
        }
        for (JsonNode value : values) {
            Candidate candidate = candidate(value);
            if (candidate != null) {
                candidates.putIfAbsent(candidate.cid2Name(), candidate);
            }
        }
    }

    private static Candidate candidate(JsonNode value) {
        if (value == null || !value.path("cid2").isIntegralNumber()
                || !value.path("cid2").canConvertToInt()
                || value.path("cid2").asInt() <= 0) {
            return null;
        }
        String name = firstText(value, "cid2Name", "cid2_name");
        if (name.isBlank()) {
            name = "cid2 " + value.path("cid2").asInt();
        }
        return new Candidate(
                value.path("cid2").asInt(),
                name,
                firstText(value, "cid1Name", "cid1_name"),
                score(value.path("interest")) ? value.path("interest").asInt() : 55,
                score(value.path("confidence")) ? value.path("confidence").asInt() : 25);
    }

    private static String firstText(JsonNode value, String... fields) {
        for (String field : fields) {
            if (value.path(field).isTextual() && !value.path(field).asText().isBlank()) {
                return value.path(field).asText().strip();
            }
        }
        return "";
    }

    private static boolean contains(JsonNode values, String expected) {
        if (!values.isArray() || expected == null || expected.isBlank()) {
            return false;
        }
        String comparable = comparable(expected);
        for (JsonNode value : values) {
            if (comparable(value.asText()).equals(comparable)) {
                return true;
            }
        }
        return false;
    }

    private static boolean score(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt()
                && value.asInt() >= 0 && value.asInt() <= 100;
    }

    private static String comparable(String value) {
        return value.codePoints()
                .filter(Character::isLetterOrDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private record Selection(
            String cid1Name,
            String cid2Name,
            int cid2,
            int interest,
            int confidence,
            JsonNode related) {
    }

    private record Candidate(int cid2, String cid2Name, String cid1Name, int interest, int confidence) {
    }
}
