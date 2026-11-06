package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.BrandNameCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.jev.OpenJevClient;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.sniffer.SnifferClient;
import com.jd.diviner.ai.rec.odin.demo.app.tools.category.SnifferRecallTools;
import org.springframework.core.io.ByteArrayResource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnifferRecallToolsTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final URI ENDPOINT = URI.create("http://diviner.jd.local/diviner");
    private static final String BASE_EXP_PARAM =
            "\"hotSkuRecallCenter.StandardRecallCenterProcessor.seedInfoConfigs\"=\"[]\"\n"
                    + "\"index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher."
                    + "KVRecallProcessor1.processorEnable\"=\"false\"\n"
                    + "\"index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher."
                    + "RetrievalProcessor8081.processorEnable\"=\"true\"\n"
                    + "\"index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.tagPriority\"=\"[8081]\"\n";

    @Test
    void i2iRequiresExplicitSkusAndIsolatesTheI2iProcessor() {
        AtomicReference<Map<String, String>> request = new AtomicReference<>();
        SnifferClient client = client((uri, body, timeout) -> {
            request.set(decode(body));
            return response("""
                    {"itemid":"100087293332","t":"玻璃水","bn":"京东养车",
                     "img":"jfs/a.png","jp":"10.00","c1":6728,"c2":6742,"c3":11849}
                    """);
        });
        var tool = new SnifferRecallTools(MAPPER, client).i2i();
        var arguments = MAPPER.createObjectNode();
        arguments.putArray("skus").add("10223978473509").add("100192030146");

        var result = tool.execute(arguments);

        assertEquals("i2i", tool.name());
        assertTrue(tool.inputSchema().contains("\"required\":[\"skus\"]"));
        assertEquals("10223978473509,100192030146", request.get().get("skus"));
        assertEquals("", request.get().get("cid3"));
        assertEquals("200", request.get().get("lim"));
        assertTrue(request.get().get("expParam").contains("RequestParamI2ITriggerBuilder"));
        assertTrue(request.get().get("expParam").contains("tagPriority\"=\"[1]"));
        assertTrue(request.get().get("expParam").contains("RetrievalProcessor8081.processorEnable\"=\"false"));
        assertTrue(request.get().get("expParam").contains("skuKnn675CutByWeight.ResultCutByWeightProcessor.limit\"=\"200"));
        assertTrue(request.get().get("expParam").contains("RequestSkuFiltersCommonFilter.filterSwitch"));
        assertEquals(2, result.path("seedSkuIds").size());
        assertEquals("100087293332", result.at("/products/0/skuId").asText());
        assertEquals(6742, result.at("/products/0/cid2").asInt());
    }

    @Test
    void createsANewRequestIdForEveryCall() {
        var requestIds = new java.util.ArrayList<String>();
        SnifferClient client = client((uri, body, timeout) -> {
            requestIds.add(decode(body).get("reqId"));
            return response("""
                    {"itemid":"100087293332","t":"商品","img":"jfs/a.png"}
                    """);
        });

        client.i2i(List.of("10223978473509"));
        client.i2i(List.of("10223978473509"));

        assertEquals(2, requestIds.size());
        assertEquals(32, requestIds.get(0).length());
        assertNotEquals(requestIds.get(0), requestIds.get(1));
    }

    @Test
    void cid2RecallUsesInterestTextWithoutCid2Filter() throws Exception {
        AtomicReference<Map<String, String>> request = new AtomicReference<>();
        SnifferClient client = client((uri, body, timeout) -> {
            request.set(decode(body));
            return response("""
                    {"itemid":"100208114889","t":"电脑商品","img":"jfs/a.png","c1":670,"c2":671,"c3":5146},
                    {"itemid":"100087293332","t":"其他类目","img":"jfs/b.png","c1":6728,"c2":6742,"c3":11849}
                    """);
        });
        var tool = new SnifferRecallTools(MAPPER, client).recallCid2();

        var result = tool.execute(MAPPER.createObjectNode().put("cid2", 671).put("cid2_name", "电脑"));

        assertTrue(tool.inputSchema().contains("\"skus\""));
        assertTrue(tool.inputSchema().contains("\"cid3\""));
        assertTrue(tool.inputSchema().contains("\"brand\""));
        assertEquals("671", request.get().get("cid2"));
        assertEquals("", request.get().get("cid3"));
        assertEquals("", request.get().get("brand"));
        assertEquals("", request.get().get("skus"));
        assertEquals("电脑", request.get().get("discoveryInterest"));
        JsonNode hint = MAPPER.readTree(request.get().get("hi"));
        assertEquals("电脑", hint.path("discoveryInterest").asText());
        assertEquals("home-rcmd", hint.path("pi").asText());
        assertEquals("电脑", MAPPER.readTree(request.get().get("matExtMsg")).path("discoveryInterest").asText());
        assertFalse(tool.inputSchema().contains("cid2_name"));
        assertFalse(tool.inputSchema().contains("discoveryInterest"));
        String expParam = request.get().get("expParam");
        assertTrue(expParam.contains("RequestParamI2ITriggerBuilder"));
        assertFalse(expParam.contains("required=true"));
        assertTrue(expParam.contains("knnSkuRecallCenter.StandardRecallCenterProcessor.processorSwitch\"=\"true"));
        assertTrue(expParam.contains("sku666603PsRanker.RankerProcessor.processorSwitch\"=\"false"));
        assertTrue(expParam.contains("sku666603PsUser.RankerUserProcessor.processorSwitch\"=\"false"));
        assertTrue(expParam.contains("RetrievalProcessor8227.processorEnable\"=\"false"));
        assertTrue(expParam.contains("RetrievalProcessor8081.processorEnable\"=\"false"));
        assertTrue(expParam.contains("tagPriority\"=\"[1]"));
        assertTrue(expParam.contains("databaseName=category_growth_v1"));
        assertTrue(expParam.contains("fieldName=cid2"));
        assertTrue(expParam.contains("div_8227brand_config"));
        assertTrue(expParam.contains("diversityName=DiversityOp"));
        assertTrue(expParam.contains("field=brand_id,limit=3"));
        assertTrue(expParam.contains("indexDiversityConfigs"));
        assertTrue(expParam.contains("key=671,origin=request.cid2"));
        assertFalse(expParam.contains("KnnCid2Filter"));
        assertTrue(expParam.contains("NcaCidTriggerBuilder"));
        assertTrue(expParam.contains("recallTagId=675,limit=200"));
        assertTrue(expParam.contains("skuKnn675CutByWeight.ResultCutByWeightProcessor.limit\"=\"200"));
        assertEquals("200", request.get().get("lim"));
        assertFalse(expParam.contains("fieldName=cb_cid2"));
        assertFalse(expParam.contains("cb_cid3"));
        assertEquals(2, result.path("count").asInt());
        assertEquals("100208114889", result.at("/skuIds/0").asText());
        assertEquals("100087293332", result.at("/skuIds/1").asText());
        assertEquals(671, result.at("/products/0/cid2").asInt());
        assertEquals(671, result.at("/constraints/cid2").asInt());
        assertTrue(result.at("/constraints/cid3").isMissingNode());
        assertTrue(result.at("/constraints/brand").isMissingNode());
    }

    @Test
    void recallSendsFilterSkusAndKeepsContainerResults() throws Exception {
        AtomicReference<Map<String, String>> request = new AtomicReference<>();
        SnifferClient client = client((uri, body, timeout) -> {
            request.set(decode(body));
            return response("""
                    {"itemid":"100208114889","t":"已曝光","bn":"甲","img":"jfs/a.png","c1":670,"c2":671,"c3":5146},
                    {"itemid":"100087293332","t":"未曝光","bn":"乙","img":"jfs/b.png","c1":670,"c2":671,"c3":5146}
                    """);
        });
        var arguments = MAPPER.createObjectNode().put("cid2", 671);
        arguments.putArray("exposedSkuIds").add("100208114889");

        var tool = new SnifferRecallTools(MAPPER, client).recallCid2();
        var result = tool.execute(arguments);

        assertFalse(tool.inputSchema().contains("exposedSkuIds"));
        assertEquals("100208114889", request.get().get("filterSkus"));
        assertEquals("100208114889", MAPPER.readTree(request.get().get("hi")).path("filterSkus").asText());
        assertEquals("100208114889", MAPPER.readTree(request.get().get("matExtMsg")).path("filterSkus").asText());
        assertEquals(1, result.path("count").asInt());
        assertEquals("100087293332", result.at("/products/0/skuId").asText());
    }

    @Test
    void recallDropsExposedImageAndKeepsFreshCards() throws Exception {
        AtomicReference<Map<String, String>> request = new AtomicReference<>();
        SnifferClient client = client((uri, body, timeout) -> {
            request.set(decode(body));
            return response("""
                    {"itemid":"100208114889","t":"已曝光","bn":"甲","img":"jfs/t1/a.png","c1":670,"c2":671,"c3":5146},
                    {"itemid":"100087293332","t":"同图未曝光","bn":"乙","img":"jfs/t1/a.png","c1":670,"c2":671,"c3":5146},
                    {"itemid":"100366920285","t":"新图一","bn":"丙","img":"jfs/t1/b.png","c1":670,"c2":671,"c3":5146},
                    {"itemid":"100366920286","t":"新图二","bn":"丁","img":"jfs/t1/c.png","c1":670,"c2":671,"c3":5146}
                    """);
        });
        var arguments = MAPPER.createObjectNode().put("cid2", 671);
        var exposed = arguments.putArray("exposedSkuIds");
        for (int index = 0; index < 16; index++) {
            exposed.add(String.valueOf(100208114889L + index));
        }

        var result = new SnifferRecallTools(MAPPER, client).recallCid2().execute(arguments);

        assertEquals("200", request.get().get("lim"));
        assertEquals("100208114889,100208114890,100208114891,100208114892,100208114893,100208114894,100208114895,100208114896,100208114897,100208114898,100208114899,100208114900,100208114901,100208114902,100208114903,100208114904",
                request.get().get("filterSkus"));
        assertEquals(request.get().get("filterSkus"),
                MAPPER.readTree(request.get().get("hi")).path("filterSkus").asText());
        assertEquals(request.get().get("filterSkus"),
                MAPPER.readTree(request.get().get("matExtMsg")).path("filterSkus").asText());
        assertEquals(2, result.path("count").asInt());
        assertEquals("100366920285", result.at("/products/0/skuId").asText());
        assertEquals("100366920286", result.at("/products/1/skuId").asText());
    }

    @Test
    void cid2OnlyFallsBackToCategoryGrowthWhenKnnFails() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Map<String, String>> second = new AtomicReference<>();
        SnifferClient client = client((uri, body, timeout) -> {
            if (calls.getAndIncrement() == 0) {
                return "{\"success\":false,\"error_msg\":\"RC_SERVICE_FAIL\"}".getBytes(StandardCharsets.UTF_8);
            }
            second.set(decode(body));
            return response("""
                    {"itemid":"100208114889","t":"电脑商品","img":"jfs/a.png","c1":670,"c2":671,"c3":5146}
                    """);
        });

        var result = new SnifferRecallTools(MAPPER, client).recallCid2()
                .execute(MAPPER.createObjectNode().put("cid2", 671));

        assertEquals(2, calls.get());
        assertTrue(second.get().get("expParam").contains("RetrievalProcessor8227.processorEnable\"=\"true"));
        assertTrue(second.get().get("expParam").contains("knnSkuRecallCenter.StandardRecallCenterProcessor.processorSwitch\"=\"false"));
        assertEquals(1, result.path("count").asInt());
        assertEquals("100208114889", result.at("/skuIds/0").asText());
    }

    @Test
    void brandRecallFallsBackToCategoryGrowthWhenEmpty() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Map<String, String>> second = new AtomicReference<>();
        SnifferClient client = client((uri, body, timeout) -> {
            if (calls.getAndIncrement() == 0) {
                return "{\"success\":false,\"error_msg\":\"RC_SERVICE_FAIL\"}".getBytes(StandardCharsets.UTF_8);
            }
            second.set(decode(body));
            return response("""
                    {"itemid":"100208114889","t":"摄影摄像","img":"jfs/a.png","c1":652,"c2":654,"c3":5012}
                    """);
        });

        var result = new SnifferRecallTools(MAPPER, client).recallCid2()
                .execute(MAPPER.createObjectNode().put("cid2", 654).put("brand", "14026"));

        assertEquals(2, calls.get());
        assertTrue(second.get().get("expParam").contains("RetrievalProcessor8227.processorEnable\"=\"true"));
        assertEquals(1, result.path("count").asInt());
        assertEquals("100208114889", result.at("/skuIds/0").asText());
    }

    @Test
    void retriesCategoryGrowthWhenFirstCardsHaveNoTitles() {
        AtomicInteger calls = new AtomicInteger();
        SnifferClient client = client((uri, body, timeout) -> {
            int n = calls.getAndIncrement();
            if (n == 0) {
                return "{\"success\":false,\"error_msg\":\"RC_SERVICE_FAIL\"}".getBytes(StandardCharsets.UTF_8);
            }
            if (n == 1) {
                return response("""
                        {"itemid":"100208114889","t":"","img":"jfs/a.png","c1":0,"c2":0,"c3":0}
                        """);
            }
            return response("""
                    {"itemid":"100208114889","t":"摄影摄像","img":"jfs/a.png","c1":652,"c2":654,"c3":5012}
                    """);
        });

        var result = new SnifferRecallTools(MAPPER, client).recallCid2()
                .execute(MAPPER.createObjectNode().put("cid2", 654));

        assertEquals(3, calls.get());
        assertEquals(1, result.path("count").asInt());
        assertEquals("100208114889", result.at("/products/0/skuId").asText());
    }

    @Test
    void keepsNamedCid2CardsWhenCategoryFieldIsMissing() {
        AtomicInteger calls = new AtomicInteger();
        SnifferClient client = client((uri, body, timeout) -> {
            if (calls.getAndIncrement() == 0) {
                return "{\"success\":false,\"error_msg\":\"RC_SERVICE_FAIL\"}".getBytes(StandardCharsets.UTF_8);
            }
            return response("""
                    {"itemid":"100208114889","t":"摄影摄像","img":"jfs/a.png"}
                    """);
        });

        var result = new SnifferRecallTools(MAPPER, client).recallCid2()
                .execute(MAPPER.createObjectNode().put("cid2", 654));

        assertEquals(1, result.path("count").asInt());
        assertEquals("100208114889", result.at("/products/0/skuId").asText());
    }

    @Test
    void optionalCid3JoinsTermWeightRecallAndBrandNameIsNotAFilter() {
        AtomicReference<Map<String, String>> request = new AtomicReference<>();
        SnifferClient client = client((uri, body, timeout) -> {
            request.set(decode(body));
            return response("""
                    {"itemid":"100021128110","t":"JBL 耳机","bn":"JBL",
                     "img":"jfs/a.png","c1":670,"c2":828,"c3":31012},
                    {"itemid":"100021128111","t":"其他品牌耳机","bn":"其他品牌",
                     "img":"jfs/b.png","c1":670,"c2":828,"c3":31012},
                    {"itemid":"100021128112","t":"其他三级类目","bn":"JBL",
                     "img":"jfs/c.png","c1":670,"c2":828,"c3":31013}
                    """);
        });
        var arguments = MAPPER.createObjectNode()
                .put("cid2", 828)
                .put("cid3", 31012)
                .put("brand", "JBL");

        var result = new SnifferRecallTools(MAPPER, client).recallCid2().execute(arguments);

        assertEquals("828", request.get().get("cid2"));
        assertEquals("31012", request.get().get("cid3"));
        assertEquals("", request.get().get("brand"));
        String expParam = request.get().get("expParam");
        assertTrue(expParam.contains("RetrievalProcessor8081.processorEnable\"=\"true"));
        assertTrue(expParam.contains("RetrievalProcessor8227.processorEnable\"=\"false"));
        assertEquals("", request.get().get("skus"));
        assertTrue(expParam.contains("tagPriority\"=\"[1,8081]"));
        assertTrue(expParam.contains("fieldName=cb_cid3"));
        assertTrue(expParam.contains("key=31012,origin=request.cid3"));
        assertFalse(expParam.contains("fieldName=cb_cid2"));
        assertFalse(expParam.contains("fieldName=cid2"));
        assertFalse(expParam.contains("cb_brand_id"));
        assertEquals(2, result.path("count").asInt());
        assertEquals("100021128110", result.at("/products/0/skuId").asText());
        assertEquals("100021128111", result.at("/products/1/skuId").asText());
        assertEquals(31012, result.at("/constraints/cid3").asInt());
        assertEquals("JBL", result.at("/constraints/brand").asText());
    }

    @Test
    void numericBrandIdJoinsCbBrandRecall() {
        AtomicReference<Map<String, String>> request = new AtomicReference<>();
        SnifferClient client = client((uri, body, timeout) -> {
            request.set(decode(body));
            return response("""
                    {"itemid":"100021128110","t":"品牌商品","bn":"281607",
                     "img":"jfs/a.png","c1":670,"c2":828,"c3":31012},
                    {"itemid":"100021128111","t":"同类商品","bn":"其他",
                     "img":"jfs/b.png","c1":670,"c2":828,"c3":31012}
                    """);
        });
        var arguments = MAPPER.createObjectNode().put("cid2", 828).put("brand", "281607");

        var result = new SnifferRecallTools(MAPPER, client).recallCid2().execute(arguments);

        assertEquals("281607", request.get().get("brand"));
        String expParam = request.get().get("expParam");
        assertTrue(expParam.contains("RetrievalProcessor8081.processorEnable\"=\"true"));
        assertTrue(expParam.contains("RetrievalProcessor8227.processorEnable\"=\"false"));
        assertTrue(expParam.contains("fieldName=cb_brand_id"));
        assertTrue(expParam.contains("key=281607,origin=request.brand"));
        assertFalse(expParam.contains("fieldName=cid2"));
        assertFalse(expParam.contains("cb_cid3"));
        assertEquals(2, result.path("count").asInt());
        assertEquals("281607", result.at("/constraints/brand").asText());
    }

    @Test
    void brandNameUsesTheMappingTable() throws Exception {
        var brands = new BrandNameCatalog(new ByteArrayResource("""
                brand_code,brandname_cn
                -1,未知
                14026,苹果
                25113,苹果
                170258,Apple
                16225,丝路（Silu）
                """.getBytes(StandardCharsets.UTF_8)));
        AtomicReference<Map<String, String>> request = new AtomicReference<>();
        SnifferClient client = client((uri, body, timeout) -> {
            request.set(decode(body));
            return response("""
                    {"itemid":"100021128110","t":"苹果手机","bn":"苹果",
                     "img":"jfs/a.png","c1":9987,"c2":653,"c3":655}
                    """);
        });
        var tool = new SnifferRecallTools(MAPPER, client, brands).recallCid2();

        var apple = tool.execute(MAPPER.createObjectNode().put("cid2", 653).put("cid2_name", "手机").put("brand", "苹果"));
        assertEquals("", request.get().get("brand"));
        assertEquals("手机|苹果", request.get().get("discoveryInterest"));
        assertTrue(request.get().get("expParam").contains("recallTagId=675"));
        assertFalse(request.get().get("expParam").contains("fieldName=cb_brand_id"));
        assertEquals("苹果", apple.at("/constraints/brand").asText());
        assertTrue(apple.at("/constraints/brandId").isMissingNode());

        var alias = tool.execute(MAPPER.createObjectNode().put("cid2", 653).put("brand", "Silu"));
        assertEquals("", request.get().get("brand"));
        assertEquals("Silu", request.get().get("discoveryInterest"));
        assertEquals("Silu", alias.at("/constraints/brand").asText());

        var bilingual = tool.execute(MAPPER.createObjectNode().put("cid2", 653).put("cid2_name", "手机").put("brand", "Apple/苹果"));
        assertEquals("", request.get().get("brand"));
        assertEquals("手机|Apple/苹果", request.get().get("discoveryInterest"));
        assertTrue(MAPPER.readTree(request.get().get("hi")).path("discoveryInterest").asText().contains("Apple/苹果"));
        assertFalse(request.get().get("expParam").contains("fieldName=cb_brand_id"));
        assertEquals("Apple/苹果", bilingual.at("/constraints/brand").asText());

        tool.execute(MAPPER.createObjectNode().put("cid2", 653).put("brand", "Apple"));
        assertEquals("", request.get().get("brand"));
        assertEquals("Apple", request.get().get("discoveryInterest"));
        tool.execute(MAPPER.createObjectNode().put("cid2", 653).put("brand", "apple"));
        assertEquals("apple", request.get().get("discoveryInterest"));
    }

    @Test
    void unmatchedBrandIsChosenByTheSmallModel() {
        var brands = new BrandNameCatalog(new ByteArrayResource("""
                brand_code,brandname_cn
                100,华为
                200,HUAWEI
                14026,苹果
                """.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, brands.candidates("华为/HUAWEI", 8).size());
        assertFalse(brands.candidates("苹果官方", 8).isEmpty());
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Map<String, String>> request = new AtomicReference<>();
        SnifferClient client = client((uri, body, timeout) -> {
            request.set(decode(body));
            return response("""
                    {"itemid":"100021128110","t":"华为手机","bn":"华为",
                     "img":"jfs/a.png","c1":9987,"c2":653,"c3":655}
                    """);
        });
        var tool = new SnifferRecallTools(MAPPER, client, brands, brandModel(calls, "100")).recallCid2();

        var chosen = tool.execute(MAPPER.createObjectNode().put("cid2", 653).put("cid2_name", "手机").put("brand", "华为/HUAWEI"));
        assertEquals(0, calls.get());
        assertEquals("", request.get().get("brand"));
        assertEquals("手机|华为/HUAWEI", request.get().get("discoveryInterest"));
        assertTrue(request.get().get("expParam").contains("recallTagId=675"));
        assertFalse(request.get().get("expParam").contains("fieldName=cb_brand_id"));
        assertEquals("华为/HUAWEI", chosen.at("/constraints/brand").asText());
        assertTrue(chosen.at("/constraints/brandId").isMissingNode());
        assertTrue(chosen.at("/constraints/brandDecision").isMissingNode());

        var contained = new SnifferRecallTools(MAPPER, client, brands, brandModel(calls, "14026")).recallCid2()
                .execute(MAPPER.createObjectNode().put("cid2", 653).put("brand", "苹果官方"));
        assertEquals(0, calls.get());
        assertEquals("苹果官方", request.get().get("discoveryInterest"));
        assertTrue(contained.at("/constraints/brandId").isMissingNode());

        new SnifferRecallTools(MAPPER, client, brands, brandModel(calls, "14026")).recallCid2()
                .execute(MAPPER.createObjectNode().put("cid2", 653).put("brand", "苹果"));
        assertEquals(0, calls.get());
        assertEquals("", request.get().get("brand"));
        assertEquals("苹果", request.get().get("discoveryInterest"));

        var skipped = new SnifferRecallTools(MAPPER, client, brands, brandModel(calls, "不使用品牌")).recallCid2()
                .execute(MAPPER.createObjectNode().put("cid2", 653).put("brand", "华为/HUAWEI"));
        assertEquals("", request.get().get("brand"));
        assertEquals("华为/HUAWEI", skipped.at("/constraints/brand").asText());
        assertTrue(skipped.at("/constraints/brandId").isMissingNode());
    }

    @Test
    void prefersI2iWhenSkusArePresent() {
        AtomicReference<Map<String, String>> request = new AtomicReference<>();
        SnifferClient client = client((uri, body, timeout) -> {
            request.set(decode(body));
            return response("""
                    {"itemid":"100087293332","t":"相似商品","bn":"测试品牌",
                     "img":"jfs/a.png","jp":"10.00","c1":999,"c2":1000,"c3":31012}
                    """);
        });
        var arguments = MAPPER.createObjectNode().put("cid2", 671);
        arguments.putArray("skus").add("10223978473509");

        var result = new SnifferRecallTools(MAPPER, client).recallCid2().execute(arguments);

        assertEquals("10223978473509", request.get().get("skus"));
        assertTrue(request.get().get("expParam").contains("RequestParamI2ITriggerBuilder"));
        assertEquals("DIVINER_I2I", result.path("source").asText());
        assertEquals("100087293332", result.at("/products/0/skuId").asText());
        assertEquals("10223978473509", result.at("/seedSkuIds/0").asText());
        assertEquals(671, result.at("/constraints/cid2").asInt());
    }

    @Test
    void rejectsMissingOrInvalidI2iSeeds() {
        var tool = new SnifferRecallTools(MAPPER, client((uri, body, timeout) -> response(""))).i2i();

        assertThrows(IllegalArgumentException.class, () -> tool.execute(MAPPER.createObjectNode()));
        var duplicate = MAPPER.createObjectNode();
        duplicate.putArray("skus").add("10223978473509").add("10223978473509");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(duplicate));
    }

    @Test
    void rejectsInvalidCategoryConstraints() {
        var tool = new SnifferRecallTools(
                MAPPER, client((uri, body, timeout) -> response(""))).recallCid2();

        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(MAPPER.createObjectNode().put("cid3", 31012)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(MAPPER.createObjectNode().put("cid2", 828).put("brand", " ")));
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(MAPPER.createObjectNode().put("cid2", 828).put("unknown", true)));
    }

    @Test
    void dropsDuplicateSkuAndSameImageCards() {
        SnifferClient client = client((uri, body, timeout) -> response("""
                {"itemid":"100087293332","t":"玻璃水A","img":"https://img.jd.com/n1/s200x200_jfs/t1/a.png!q70.webp"},
                {"itemid":"100087293332","t":"重复 SKU","img":"jfs/t1/other.png"},
                {"itemid":"100366920284","t":"相同主图","img":"jfs/t1/a.png"},
                {"itemid":"100366920285","t":"玉米油","img":"jfs/t1/b.jpg"}
                """));

        var cards = client.i2i(List.of("10231202300517"));

        assertEquals(2, cards.size());
        assertEquals("100087293332", cards.get(0).skuId());
        assertEquals("100366920285", cards.get(1).skuId());
        assertEquals("jfs/t1/a.png", SnifferClient.imageFingerprint(
                "https://img.jd.com/n1/s200x200_jfs/t1/a.png!q70.webp"));
    }

    private static OpenJevClient brandModel(AtomicInteger calls, String prefix) {
        return new OpenJevClient(MAPPER, URI.create("https://example.invalid/v1/systemone"), "laya-1.0",
                Duration.ofSeconds(5), (uri, body, timeout) -> {
                    calls.incrementAndGet();
                    JsonNode criteria = body.at("/questions/brand/criteria");
                    String selected = "i0";
                    var fields = criteria.fields();
                    while (fields.hasNext()) {
                        var entry = fields.next();
                        if (entry.getValue().asText().startsWith(prefix) || entry.getValue().asText().equals(prefix)) {
                            selected = entry.getKey();
                            break;
                        }
                    }
                    return MAPPER.createObjectNode()
                            .put("model", "laya-1.0")
                            .set("answers", MAPPER.createObjectNode().set("brand", MAPPER.createObjectNode()
                                    .put("type", "choice")
                                    .put("choice", selected)
                                    .put("confidence", 0.8)
                                    .set("probabilities", MAPPER.createObjectNode())));
                });
    }

    private static SnifferClient client(SnifferClient.Transport transport) {
        var template = MAPPER.createObjectNode()
                .put("p", "666603")
                .put("skus", "")
                .put("cid3", "7170")
                .put("hi", "{\"pi\":\"home-rcmd\",\"page\":2,\"ci\":0,\"tryMode\":0}")
                .put("matExtMsg", "")
                .put("expParam", BASE_EXP_PARAM);
        return new SnifferClient(MAPPER, ENDPOINT, Duration.ofSeconds(5), template, transport);
    }

    private static byte[] response(String items) {
        return ("{\"success\":true,\"data\":[" + items + "]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> decode(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : body.split("&")) {
            int separator = part.indexOf('=');
            String key = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }
}
