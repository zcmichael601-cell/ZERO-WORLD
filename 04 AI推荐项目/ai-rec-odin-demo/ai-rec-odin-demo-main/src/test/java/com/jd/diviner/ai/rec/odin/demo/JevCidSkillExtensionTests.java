package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillExtensions;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import com.jd.diviner.ai.rec.odin.demo.app.application.jevcid.JevCidSkillExtension;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.Cid2NameCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.CidTopSkuCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.tools.category.CategorySelectionTools;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevCidSkillExtensionTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USERNAME = "0041d911ed9855436c475d313d43cd90aefaf7f0";

    @Test
    void runsEntireCategoryWorkflowWithoutBusinessLogicInSkillEngine() throws Exception {
        var executions = new ArrayList<String>();
        var registry = registry(executions, List.of("10223978473509", "100192030146"));
        var extensions = new SkillExtensions(List.of(new JevCidSkillExtension()));
        var engine = new SkillEngine(
                MAPPER,
                catalog(registry, extensions),
                registry,
                new SkillModelClient(firstScreenModel(), "test"));

        var run = engine.execute("shopping-recommendation-jev-cid",
                MAPPER.createObjectNode().put("username", USERNAME));

        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(1, run.modelCalls());
        assertEquals(List.of(
                "get_user_profile",
                "get_user_behaviors",
                "recall_cid2"), executions);
        assertEquals("10223978473509", run.output().at("/products/0/skuId").asText());
        assertEquals("类目商品", run.output().at("/products/0/name").asText());
        assertEquals("基建材料|类目品牌", run.output().at("/products/0/category").asText());
        assertEquals("类目品牌", run.output().at("/products/0/brand").asText());
        assertEquals("DIVINER_CID2", run.output().at("/products/0/source").asText());
        assertEquals(999, run.output().at("/products/0/cid1").asInt());
        assertEquals(1000, run.output().at("/products/0/cid2").asInt());
        assertEquals(31012, run.output().at("/products/0/cid3").asInt());
        JsonNode cid1Arguments = run.traces().stream()
                .filter(trace -> "select_cid1".equals(trace.tool()))
                .findFirst().orElseThrow().arguments();
        assertEquals("基建材料｜类目品牌", cid1Arguments.path("query").asText());
        JsonNode recallArguments = run.traces().stream()
                .filter(trace -> "recall_cid2".equals(trace.tool()))
                .findFirst().orElseThrow().arguments();
        assertEquals("基建材料|类目品牌", recallArguments.path("query").asText());
        assertEquals("类目品牌", recallArguments.path("brand").asText());
        assertEquals("类目品牌", run.output().at("/recallConstraints/brand").asText());
        assertEquals(1, run.output().path("initialInterests").size());
        assertEquals(1, run.output().at("/initialInterests/0/cid2").asInt());
        assertEquals("基建材料", run.output().at("/initialInterests/0/cid2Name").asText());
        assertEquals("基建材料|类目品牌", run.output().at("/initialInterests/0/query").asText());
        assertTrue(run.output().at("/initialInterests/0/interest").asInt() >= 50);
        assertTrue(run.output().at("/initialInterests/0/confidence").asInt() >= 10);
        assertEquals(1, run.output().at("/currentInterests/0/cid2").asInt());
        assertEquals("类目品牌", run.output().at("/currentInterests/0/brand").asText());
        assertEquals(1, run.output().at("/relatedCid2/0/cid2").asInt());
        assertEquals(0, run.output().at("/interaction/interestBaselineScreen").asInt());
        assertTrue(run.output().path("seedSkuIds").isEmpty());
        assertEquals("10223978473509", run.output().at("/categoryRecallSkuIds/0").asText());
        assertTrue(run.output().path("profile").isObject());
    }

    @Test
    void refreshesNextScreenDirectlyFromSimulatedClickSeeds() throws Exception {
        var executions = new ArrayList<String>();
        var clicked = List.of("100087293332", "100235204575");
        var registry = registry(executions, clicked);
        var extensions = new SkillExtensions(List.of(new JevCidSkillExtension()));
        var engine = new SkillEngine(
                MAPPER,
                catalog(registry, extensions),
                registry,
                new SkillModelClient(unusedModel(), "test"));
        ObjectNode input = MAPPER.createObjectNode()
                .put("username", USERNAME)
                .put("screenNumber", 2);
        input.putArray("simulatedClickSkuIds").add(clicked.get(0)).add(clicked.get(1));
        input.putArray("exposedSkuIds").add(clicked.get(0)).add(clicked.get(1)).add("100345231192");
        addSessionContext(input, List.of(clicked.get(0), clicked.get(1), "100345231192"), clicked);

        var run = engine.execute("shopping-recommendation-jev-cid", input);

        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(0, run.modelCalls());
        assertEquals(List.of("get_user_profile", "get_user_behaviors", "recall_cid2"), executions);
        assertEquals("CATEGORY_AND_CLICK_I2I", run.output().at("/interaction/mode").asText());
        assertEquals(2, run.output().at("/interaction/screenNumber").asInt());
        assertEquals(3, run.output().at("/interaction/exposedSkuIds").size());
        assertEquals(clicked.get(0), run.output().at("/seedSkuIds/0").asText());
        assertEquals(1, run.output().path("products").size());
        assertEquals("I2I", run.output().at("/products/0/recallBranch").asText());
        assertEquals("模拟点击/相似商品", run.output().at("/products/0/category").asText());
        assertEquals(31012, run.output().at("/recallConstraints/cid3").asInt());
        assertEquals("类目品牌", run.output().at("/recallConstraints/brand").asText());
        assertEquals(run.output().at("/recallConstraints/cid2").asInt(),
                run.output().at("/currentInterests/0/cid2").asInt());
        assertEquals(1, run.output().at("/interaction/interestBaselineScreen").asInt());
    }

    @Test
    void skipsI2iWhenAllSessionExposuresAreUnclicked() throws Exception {
        var executions = new ArrayList<String>();
        var exposed = List.of("100087293332", "100235204575");
        var registry = registry(executions, exposed);
        var engine = new SkillEngine(
                MAPPER,
                catalog(registry, new SkillExtensions(List.of(new JevCidSkillExtension()))),
                registry,
                new SkillModelClient(relatedQueryModel(), "test"));
        ObjectNode input = MAPPER.createObjectNode()
                .put("username", USERNAME)
                .put("screenNumber", 2);
        input.putArray("simulatedClickSkuIds");
        input.putArray("exposedSkuIds").add(exposed.get(0)).add(exposed.get(1));
        addSessionContext(input, exposed, List.of());

        var run = engine.execute("shopping-recommendation-jev-cid", input);

        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(1, run.modelCalls());
        assertEquals(List.of("get_user_profile", "get_user_behaviors", "recall_cid2"), executions);
        assertEquals("CATEGORY_NOVEL", run.output().at("/interaction/mode").asText());
        assertTrue(run.output().at("/interaction/simulatedClickSkuIds").isEmpty());
        assertTrue(run.output().path("seedSkuIds").isEmpty());
        assertEquals(1, run.output().path("products").size());
        assertEquals("CATEGORY", run.output().at("/products/0/recallBranch").asText());
        assertEquals("保温材料|类目品牌", run.output().at("/products/0/category").asText());
        assertEquals(0, run.output().path("i2iRecallCount").asInt());
        JsonNode nextRecall = run.traces().stream()
                .filter(trace -> "recall_cid2".equals(trace.tool()))
                .findFirst().orElseThrow().arguments();
        assertEquals("保温材料|类目品牌", nextRecall.path("query").asText());
        assertEquals("保温材料", run.output().at("/currentInterests/0/cid2Name").asText());
        assertEquals("保温材料|类目品牌", run.output().at("/currentInterests/0/query").asText());
    }

    @Test
    void screenFourWithoutClicksExploresNovelPhoneCategories() throws Exception {
        var executions = new ArrayList<String>();
        var registry = registry(executions, List.of());
        var engine = new SkillEngine(
                MAPPER,
                catalog(registry, new SkillExtensions(List.of(new JevCidSkillExtension()))),
                registry,
                new SkillModelClient(relatedQueryModel(), "test"));
        ObjectNode input = MAPPER.createObjectNode()
                .put("username", USERNAME)
                .put("screenNumber", 4);
        input.putArray("simulatedClickSkuIds");
        input.putArray("exposedSkuIds").add("100087293332");
        addSessionContext(input, List.of("100087293332"), List.of());
        input.withArray("sessionEvents").forEach(event -> ((ObjectNode) event).put("screenNumber", 3));
        input.withArray("initialInterests").removeAll();
        input.withArray("currentInterests").removeAll();
        input.withArray("relatedCid2").removeAll();
        input.withArray("initialInterests").addObject()
                .put("cid2", 1)
                .put("cid2Name", "手机")
                .put("query", "手机|苹果")
                .put("interest", 80)
                .put("confidence", 70);
        input.set("currentInterests", input.path("initialInterests").deepCopy());
        input.withArray("relatedCid2").addObject()
                .put("cid2", 1)
                .put("cid2Name", "手机");

        var run = engine.execute("shopping-recommendation-jev-cid", input);

        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(1, run.modelCalls());
        assertEquals("CATEGORY_NOVEL", run.output().at("/interaction/mode").asText());
        assertEquals(0, run.output().path("i2iRecallCount").asInt());
        assertTrue(run.output().path("seedSkuIds").isEmpty());
        assertEquals(1, run.output().path("products").size());
        assertEquals("保温材料|类目品牌", run.output().at("/products/0/category").asText());
        assertEquals("保温材料", run.output().at("/currentInterests/0/cid2Name").asText());
    }

    @Test
    void skipsEarlierClicksAndExploresWhenThisScreenHasNoClick() throws Exception {
        var executions = new ArrayList<String>();
        String earlierClick = "100087293332";
        var registry = registry(executions, List.of(earlierClick));
        var engine = new SkillEngine(
                MAPPER,
                catalog(registry, new SkillExtensions(List.of(new JevCidSkillExtension()))),
                registry,
                new SkillModelClient(relatedQueryModel(), "test"));
        ObjectNode input = MAPPER.createObjectNode()
                .put("username", USERNAME)
                .put("screenNumber", 3);
        input.putArray("simulatedClickSkuIds");
        input.putArray("exposedSkuIds").add("100235204575").add("100345231192");
        input.putArray("initialInterests").addObject()
                .put("cid1Name", "家装建材")
                .put("cid2", 1000)
                .put("cid2Name", "基建材料")
                .put("query", "基建材料|类目品牌")
                .put("interest", 72)
                .put("confidence", 46);
        input.set("currentInterests", input.path("initialInterests").deepCopy());
        input.putArray("relatedCid2").addObject()
                .put("cid1Name", "家装建材")
                .put("cid2", 1000)
                .put("cid2Name", "基建材料");
        var events = input.putArray("sessionEvents");
        events.addObject()
                .put("screenNumber", 1)
                .put("skuId", earlierClick)
                .put("name", "上一屏已点击商品")
                .put("brand", "类目品牌")
                .put("cid2", 1000)
                .put("cid3", 31012)
                .put("clicked", true);
        for (String skuId : List.of("100235204575", "100345231192")) {
            events.addObject()
                    .put("screenNumber", 2)
                    .put("skuId", skuId)
                    .put("name", "本屏未点击商品")
                    .put("brand", "类目品牌")
                    .put("cid2", 1000)
                    .put("cid3", 31012)
                    .put("clicked", false);
        }

        var run = engine.execute("shopping-recommendation-jev-cid", input);

        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(List.of("get_user_profile", "get_user_behaviors", "recall_cid2"), executions);
        assertTrue(run.output().path("seedSkuIds").isEmpty());
        assertEquals("CATEGORY_NOVEL", run.output().at("/interaction/mode").asText());
        assertEquals("保温材料|类目品牌", run.output().at("/products/0/category").asText());
    }

    @Test
    void rejectsInvalidInteractionState() {
        var extension = new JevCidSkillExtension();
        ObjectNode missingExposures = MAPPER.createObjectNode()
                .put("username", USERNAME)
                .put("screenNumber", 2);
        assertThrows(IllegalArgumentException.class,
                () -> extension.validateInput(null, missingExposures));

        ObjectNode duplicateClicks = MAPPER.createObjectNode()
                .put("username", USERNAME)
                .put("screenNumber", 2);
        duplicateClicks.putArray("simulatedClickSkuIds").add("100087293332").add("100087293332");
        duplicateClicks.putArray("exposedSkuIds").add("100087293332");
        assertThrows(IllegalArgumentException.class,
                () -> extension.validateInput(null, duplicateClicks));

        ObjectNode clickOutsideExposure = MAPPER.createObjectNode()
                .put("username", USERNAME)
                .put("screenNumber", 2);
        clickOutsideExposure.putArray("simulatedClickSkuIds").add("100087293332");
        clickOutsideExposure.putArray("exposedSkuIds").add("100235204575");
        assertThrows(IllegalArgumentException.class,
                () -> extension.validateInput(null, clickOutsideExposure));
    }

    private void addSessionContext(ObjectNode input, List<String> exposed, List<String> clicked) {
        var initialInterests = input.putArray("initialInterests");
        initialInterests.addObject()
                .put("cid1Name", "家装建材")
                .put("cid2", 1000)
                .put("cid2Name", "基建材料")
                .put("query", "基建材料|类目品牌")
                .put("interest", 72)
                .put("confidence", 46)
                .put("brand", "类目品牌");
        input.set("currentInterests", initialInterests.deepCopy());
        input.putArray("relatedCid2").addObject()
                .put("cid1Name", "家装建材")
                .put("cid2", 1000)
                .put("cid2Name", "基建材料");
        var events = input.putArray("sessionEvents");
        for (String skuId : exposed) {
            events.addObject()
                    .put("screenNumber", 1)
                    .put("skuId", skuId)
                    .put("name", "会话商品 " + skuId)
                    .put("brand", "类目品牌")
                    .put("cid2", 1000)
                    .put("cid3", 31012)
                    .put("cid2Name", "基建材料")
                    .put("cid3Name", "测试三级类目")
                    .put("category", "家装建材/基建材料")
                    .put("clicked", clicked.contains(skuId));
        }
    }

    private ToolRegistry registry(List<String> executions, List<String> expectedSeeds) {
        var catalog = new CidTopSkuCatalog(
                new ClassPathResource("catalog/cid3_top10_sku_30d_small.csv"));
        var cid2Names = new Cid2NameCatalog(new ByteArrayResource("""
                cid1,cid1_name,cid2,cid2_name,n_cid3
                999,家装建材,1000,基建材料,1
                2000,外卖美食,2001,西式主食/快餐,1
                """.getBytes(StandardCharsets.UTF_8)));
        var selection = new CategorySelectionTools(MAPPER, catalog, cid2Names);
        return new ToolRegistry(List.of(
                tool("get_user_profile", arguments -> MAPPER.createObjectNode()
                        .put("username", arguments.path("username").asText())
                        .put("source", "AI_MEMORY")
                        .put("dataStatus", "AVAILABLE")
                        .set("memories", MAPPER.createArrayNode().addObject()
                                .put("memory", "偏好家装建材和冬季保温")), executions),
                tool("get_user_behaviors", arguments -> MAPPER.createObjectNode()
                        .put("username", arguments.path("username").asText())
                        .put("source", "AI_MEMORY")
                        .put("dataStatus", "AVAILABLE")
                        .set("memories", MAPPER.createArrayNode().addObject()
                                .put("memory", "近期浏览基建材料")), executions),
                selection.selectCid1(),
                selection.selectCid2(),
                tool("recall_cid2", arguments -> {
                    if (arguments.path("skus").isArray() && !arguments.path("skus").isEmpty()) {
                        assertEquals(expectedSeeds.size(), arguments.path("skus").size());
                        for (int i = 0; i < expectedSeeds.size(); i++) {
                            assertEquals(expectedSeeds.get(i), arguments.path("skus").get(i).asText());
                        }
                        ObjectNode result = MAPPER.createObjectNode().put("source", "DIVINER_I2I");
                        result.put("cid2", 1000);
                        result.set("seedSkuIds", arguments.path("skus").deepCopy());
                        ObjectNode constraints = result.putObject("constraints").put("cid2", 1000);
                        if (arguments.has("cid3")) {
                            constraints.put("cid3", arguments.path("cid3").asInt());
                        }
                        if (arguments.has("brand")) {
                            constraints.put("brand", arguments.path("brand").asText());
                        }
                        result.putArray("products").addObject()
                                .put("skuId", "100087293332")
                                .put("name", "i2i 商品")
                                .put("brand", "测试品牌")
                                .put("cid1", 999)
                                .put("cid2", 1000)
                                .put("cid3", 31012)
                                .put("image", "jfs/test.png")
                                .put("price", 19.9);
                        result.put("count", 1);
                        return result;
                    }
                    if (arguments.path("query").isTextual()) {
                        String query = arguments.path("query").asText();
                        ObjectNode queried = MAPPER.createObjectNode().put("source", "DIVINER_CID2");
                        queried.put("query", query);
                        if (arguments.path("brand").isTextual()) {
                            queried.putObject("constraints").put("brand", arguments.path("brand").asText());
                        }
                        queried.putArray("skuIds").add("10223978473509").add("100192030146");
                        queried.putArray("products").addObject()
                                .put("skuId", "10223978473509")
                                .put("name", "类目商品")
                                .put("brand", "类目品牌")
                                .put("cid1", 999)
                                .put("cid2", 1000)
                                .put("cid3", 31012)
                                .put("image", "jfs/category.png")
                                .put("price", 29.9);
                        queried.put("count", 1);
                        return queried;
                    }
                    int requestedCid2 = arguments.path("cid2").asInt();
                    if (requestedCid2 == 654 || requestedCid2 == 12800 || requestedCid2 == 49061) {
                        ObjectNode explored = MAPPER.createObjectNode().put("source", "DIVINER_CID2");
                        explored.put("cid2", requestedCid2);
                        explored.putArray("skuIds").add("10000000000" + requestedCid2);
                        explored.putArray("products").addObject()
                                .put("skuId", "10000000000" + requestedCid2)
                                .put("name", "探索商品 " + requestedCid2)
                                .put("brand", "测试品牌")
                                .put("cid1", requestedCid2 == 12800 ? 670 : 652)
                                .put("cid2", requestedCid2)
                                .put("cid3", 1)
                                .put("image", "jfs/explore.png")
                                .put("price", 39.9);
                        explored.put("count", 1);
                        return explored;
                    }
                    assertEquals(1000, requestedCid2);
                    ObjectNode result = MAPPER.createObjectNode().put("source", "DIVINER_CID2");
                    result.put("cid2", 1000);
                    ObjectNode constraints = result.putObject("constraints").put("cid2", 1000);
                    if (arguments.has("cid3")) {
                        assertEquals(31012, arguments.path("cid3").asInt());
                        constraints.put("cid3", arguments.path("cid3").asInt());
                    }
                    if (arguments.has("brand")) {
                        assertEquals("类目品牌", arguments.path("brand").asText());
                        constraints.put("brand", arguments.path("brand").asText());
                    }
                    result.putArray("skuIds").add("10223978473509").add("100192030146");
                    result.putArray("products").addObject()
                            .put("skuId", "10223978473509")
                            .put("name", "类目商品")
                            .put("brand", "类目品牌")
                            .put("cid1", 999)
                            .put("cid2", 1000)
                            .put("cid3", 31012)
                            .put("image", "jfs/category.png")
                            .put("price", 29.9);
                    result.put("count", 1);
                    return result;
                }, executions)));
    }

    private SkillTool tool(
            String name,
            java.util.function.Function<JsonNode, JsonNode> operation,
            List<String> executions) {
        return new SkillTool() {
            public String name() { return name; }
            public String description() { return "测试工具 " + name; }
            public String inputSchema() {
                return "{\"type\":\"object\",\"additionalProperties\":true}";
            }
            public String dataMode() { return "LIVE"; }
            public JsonNode execute(JsonNode arguments) {
                executions.add(name);
                return operation.apply(arguments);
            }
        };
    }

    private SkillCatalog catalog(ToolRegistry tools, SkillExtensions extensions) throws Exception {
        var root = MAPPER.createObjectNode().put("version", 2);
        var skill = root.putArray("skills").addObject()
                .put("id", "shopping-recommendation-jev-cid")
                .put("name", "类目意图推荐")
                .put("description", "扩展工作流测试")
                .put("enabled", true)
                .put("handler", "jev-cid")
                .put("executionMode", "WORKFLOW")
                .put("instructions", "shopping-recommendation-jev-cid/SKILL.md")
                .put("maxModelCalls", 3)
                .put("maxToolCalls", 5);
        skill.putArray("requiredInputs").add("username");
        for (String tool : List.of("get_user_profile", "get_user_behaviors", "select_cid1", "recall_cid2")) {
            skill.withArray("tools").add(tool);
        }
        for (String tool : List.of("get_user_profile", "get_user_behaviors", "recall_cid2")) {
            skill.withArray("requiredTools").add(tool);
        }
        skill.putArray("requiredOutputs").add("products");
        Resource resource = new ByteArrayResource(MAPPER.writeValueAsBytes(root)) {
            @Override
            public Resource createRelative(String path) {
                return new ByteArrayResource("""
                        ---
                        name: shopping-recommendation-jev-cid
                        description: 类目意图工作流测试
                        ---
                        首屏由模型决策 cid1 和 cid2，后续屏由代码沿用当前 cid2 召回。
                        """.getBytes(StandardCharsets.UTF_8));
            }
        };
        return new SkillCatalog(MAPPER, resource, tools, extensions);
    }

    private ChatModel unusedModel() {
        return prompt -> {
            throw new AssertionError("后续屏不应再调用模型决策类目");
        };
    }

    private ChatModel firstScreenModel() {
        return new ChatModel() {
            private int calls;

            @Override
            public ChatResponse call(Prompt prompt) {
                try {
                    JsonNode facts = MAPPER.readTree(prompt.getInstructions().get(1).getText());
                    assertEquals(1, facts.at("/availableTools").size());
                    assertEquals(1, facts.at("/input/screenNumber").asInt(1));
                    ObjectNode arguments = MAPPER.createObjectNode();
                    String name;
                    if (calls++ == 0) {
                        assertEquals("select_cid1", facts.at("/availableTools/0/name").asText());
                        assertTrue(facts.at("/availableTools/0/description").asText()
                                .contains("产品词｜品牌"));
                        name = "select_cid1";
                        arguments.put("query", "基建材料｜类目品牌");
                    } else {
                        throw new AssertionError("首屏不应再让模型决策 recall_cid2");
                    }
                    String decision = MAPPER.createObjectNode()
                            .put("name", name)
                            .set("arguments", arguments)
                            .toString();
                    return new ChatResponse(List.of(new Generation(
                            new AssistantMessage(decision),
                            ChatGenerationMetadata.builder().finishReason("stop").build())));
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }
        };
    }

    private ChatModel relatedQueryModel() {
        return prompt -> {
            try {
                JsonNode facts = MAPPER.readTree(prompt.getInstructions().get(1).getText());
                assertEquals(1, facts.at("/availableTools").size());
                assertEquals("select_cid1", facts.at("/availableTools/0/name").asText());
                String description = facts.at("/availableTools/0/description").asText();
                assertTrue(description.contains("相关"));
                assertTrue(description.contains("已经用过的产品词"));
                assertTrue(description.contains("基建材料") || description.contains("手机"));
                ObjectNode arguments = MAPPER.createObjectNode().put("query", "保温材料｜类目品牌");
                String decision = MAPPER.createObjectNode()
                        .put("name", "select_cid1")
                        .set("arguments", arguments)
                        .toString();
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage(decision),
                        ChatGenerationMetadata.builder().finishReason("stop").build())));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        };
    }
}
