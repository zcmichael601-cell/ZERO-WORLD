package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.domain.category.CidTopSkuCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.jev.OpenJevClient;
import com.jd.diviner.ai.rec.odin.demo.app.tools.category.CategoryIntentTools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryIntentToolsTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void inferRecallsAndRanksWithAtMostTenSkus() {
        var catalog = new CidTopSkuCatalog(new ClassPathResource("catalog/cid3_top10_sku_30d_small.csv"));
        AtomicInteger calls = new AtomicInteger();
        OpenJevClient.Transport transport = (uri, body, timeout) -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                assertEquals("用户常点汉堡 咖啡", body.path("state").asText());
            }
            String question = body.path("questions").fieldNames().next();
            if ("noul".equals(body.path("questions").path(question).path("type").asText())) {
                assertTrue(body.path("questions").size() >= 1);
                var answers = MAPPER.createObjectNode();
                boolean first = true;
                var names = body.path("questions").fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    answers.set(name, MAPPER.createObjectNode().put("type", "noul")
                            .put("noul", n == 3 ? 0.01 : (first ? 0.9 : 0.1)));
                    first = false;
                }
                return MAPPER.createObjectNode().put("model", "laya-1.0").set("answers", answers);
            }
            ObjectNode criteria = (ObjectNode) body.path("questions").path(question).path("criteria");
            String firstKey = criteria.fieldNames().next();
            assertEquals("i0", firstKey);
            assertTrue(criteria.path(firstKey).isTextual());
            return MAPPER.createObjectNode()
                    .put("model", "laya-1.0")
                    .set("answers", MAPPER.createObjectNode().set(question, MAPPER.createObjectNode()
                            .put("type", "choice")
                            .put("choice", firstKey)
                            .put("confidence", 0.2)
                            .set("probabilities", MAPPER.createObjectNode().put(firstKey, 0.55))));
        };
        var jev = new OpenJevClient(MAPPER, URI.create("https://example.invalid/v1/systemone"), "laya-1.0",
                Duration.ofSeconds(5), transport);
        var tools = new CategoryIntentTools(MAPPER, jev, catalog);

        var inferred = tools.inferCategory().execute(MAPPER.createObjectNode().put("interestSummary", "用户常点汉堡\n咖啡"));
        assertEquals(3, calls.get());
        assertTrue(inferred.path("cid3").path("lowConfidence").asBoolean());

        var recalled = tools.recallTopn().execute(MAPPER.createObjectNode()
                .put("cid1_name", inferred.path("cid1_name").asText())
                .put("cid2_name", inferred.path("cid2_name").asText())
                .put("cid3_name", inferred.path("cid3_name").asText())
                .put("limit", 10));
        assertTrue(recalled.path("products").size() <= 10);

        var skuIds = MAPPER.createArrayNode();
        recalled.path("products").forEach(product -> skuIds.add(product.path("skuId").asText()));
        if (skuIds.isEmpty()) {
            skuIds.add("100194124737");
        }
        var ranked = tools.rankCandidates().execute(MAPPER.createObjectNode()
                .put("interestSummary", "喜欢汉堡")
                .put("categoryPath", inferred.path("categoryPath").asText())
                .set("skuIds", skuIds));
        assertTrue(ranked.path("orderedSkuIds").isArray());
        assertTrue(ranked.path("orderedSkuIds").size() <= 10);
        assertEquals(4, calls.get());
    }

    @Test
    void rejectsMoreThanTenSkuIds() {
        var catalog = new CidTopSkuCatalog(new ClassPathResource("catalog/cid3_top10_sku_30d_small.csv"));
        var jev = new OpenJevClient(MAPPER, URI.create("https://example.invalid/v1/systemone"), "laya-1.0",
                Duration.ofSeconds(5), (uri, body, timeout) -> MAPPER.createObjectNode());
        var tools = new CategoryIntentTools(MAPPER, jev, catalog);
        var skuIds = MAPPER.createArrayNode();
        for (int i = 0; i < 11; i++) {
            skuIds.add(String.valueOf(1000000 + i));
        }
        assertThrows(IllegalArgumentException.class, () -> tools.rankCandidates().execute(MAPPER.createObjectNode()
                .put("interestSummary", "test")
                .put("categoryPath", "a/b/c")
                .set("skuIds", skuIds)));
    }
}
