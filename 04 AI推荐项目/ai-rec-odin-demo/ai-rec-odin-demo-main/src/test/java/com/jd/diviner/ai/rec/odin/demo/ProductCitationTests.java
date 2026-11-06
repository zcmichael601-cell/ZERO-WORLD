package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation.ProductComparisonValidator;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProductCitationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORIGINAL = "容量：\n  256GB\t\t存储；\u00a0不支持扩展。";

    private ObjectNode output(String quote, String id) {
        var output = MAPPER.createObjectNode().put("comparisonSummary", "依据当前规格比较");
        var comparison = output.putArray("products").addObject().put("productId", "SKU-001")
                .putObject("comparison").put("status", "PARTIAL_EVIDENCE").put("fit", "适用需求需核实").put("uncertainty", "仅页面标注");
        comparison.putArray("strengths");
        comparison.putArray("limitations");
        comparison.putArray("dimensions").addObject().put("dimension", "存储").put("text", "页面容量与扩展条件")
                .put("evidenceId", id).put("quote", quote);
        return output;
    }

    private JsonNode evidence(String id, String productId, String text) {
        var knowledge = MAPPER.createObjectNode();
        knowledge.putArray("evidence").addObject().put("id", id).put("productId", productId).put("text", text);
        return knowledge;
    }

    @Test
    void selectedQuoteIsFilledFromTheSameSkuEvidenceAndCannotOverrideFabricatedText() {
        var knowledge = evidence("K1", "SKU-001", ORIGINAL);
        ((ObjectNode) knowledge.at("/evidence/0")).putArray("quoteChoices").addObject().put("id", "Q1").put("text", ORIGINAL);
        var output = output("", "K1");
        var claim = (ObjectNode) output.at("/products/0/comparison/dimensions/0");
        claim.remove("quote");
        claim.put("quoteId", "Q1");
        ProductComparisonValidator.validate(output, Map.of("SKU-001", knowledge), true);
        assertEquals(ORIGINAL, claim.path("quote").asText());
        claim.put("quote", "容量512GB且支持扩展");
        var failure = assertThrows(ProductComparisonValidator.CitationFailure.class,
                () -> ProductComparisonValidator.validate(output, Map.of("SKU-001", knowledge), true));
        assertEquals("QUOTE_SELECTION_CONFLICT", failure.issues().get(0).reason());
    }

    @Test
    void rejectsUnknownCrossSkuAndCorruptQuoteChoices() {
        for (String productId : new String[] {"SKU-001", "SKU-002"}) {
            var knowledge = evidence("K1", productId, ORIGINAL);
            ((ObjectNode) knowledge.at("/evidence/0")).putArray("quoteChoices").addObject().put("id", "Q1").put("text", "根本不存在的原文摘录");
            var output = output("", "K1");
            var claim = (ObjectNode) output.at("/products/0/comparison/dimensions/0");
            claim.remove("quote");
            claim.put("quoteId", "Q1");
            assertThrows(ProductComparisonValidator.CitationFailure.class,
                    () -> ProductComparisonValidator.validate(output, Map.of("SKU-001", knowledge), true));
            claim.put("quoteId", "Q999");
            assertThrows(ProductComparisonValidator.CitationFailure.class,
                    () -> ProductComparisonValidator.validate(output, Map.of("SKU-001", knowledge), true));
        }
    }

    @Test
    void restoresWhitespaceToAnExactContiguousSourceWithoutChangingEvidence() {
        var knowledge = evidence("K1", "SKU-001", ORIGINAL);
        var output = output("容量： 256GB 存储； 不支持扩展。", "K1");
        ProductComparisonValidator.validate(output, Map.of("SKU-001", knowledge), true);
        assertEquals(ORIGINAL, output.at("/products/0/comparison/dimensions/0/quote").asText());
        assertEquals(ORIGINAL, output.at("/knowledgeSources/0/text").asText());
        assertEquals("K1", output.at("/products/0/comparison/dimensions/0/evidenceId").asText());
    }

    @Test
    void neverRepairsChangedValuesNegationPunctuationOrConcatenatedSnippets() {
        for (String quote : new String[] {"容量： 512GB 存储； 不支持扩展。", "容量： 256GB 存储； 支持扩展。",
                "容量：256GB存储；不支持扩展。", "容量: 256GB 存储； 不支持扩展。", "容量：……不支持扩展。"}) {
            var exception = assertThrows(ProductComparisonValidator.CitationFailure.class,
                    () -> ProductComparisonValidator.validate(output(quote, "K1"), Map.of("SKU-001", evidence("K1", "SKU-001", ORIGINAL)), true));
            assertEquals("QUOTE_NOT_IN_SOURCE", exception.issues().get(0).reason());
            assertEquals("/products/0/comparison/dimensions/0", exception.issues().get(0).path());
            assertFalse(exception.getMessage().contains(quote));
        }
    }

    @Test
    void detectsUnknownAndCrossProductIdsEvenWhenTextMatches() {
        var knowledge = Map.of("SKU-001", evidence("K1", "SKU-001", ORIGINAL), "SKU-002", evidence("K2", "SKU-002", ORIGINAL));
        var unknown = assertThrows(ProductComparisonValidator.CitationFailure.class,
                () -> ProductComparisonValidator.validate(output(ORIGINAL, "invented"), knowledge, true));
        assertEquals("EVIDENCE_NOT_FOUND", unknown.issues().get(0).reason());
        var crossProduct = assertThrows(ProductComparisonValidator.CitationFailure.class,
                () -> ProductComparisonValidator.validate(output(ORIGINAL, "K2"), knowledge, true));
        assertEquals("PRODUCT_MISMATCH", crossProduct.issues().get(0).reason());
    }

    @Test
    void collectsAllInvalidCitationsAndDoesNotPublishPartialSources() {
        var output = output("不是原文的错误引文", "K1");
        var comparison = (ObjectNode) output.at("/products/0/comparison");
        comparison.putArray("strengths").addObject().put("text", "不可证实结论").put("quote", "另一条无效的引文").put("evidenceId", "invalid");
        var exception = assertThrows(ProductComparisonValidator.CitationFailure.class,
                () -> ProductComparisonValidator.validate(output, Map.of("SKU-001", evidence("K1", "SKU-001", ORIGINAL)), true));
        assertEquals(2, exception.issues().size());
        assertFalse(output.has("knowledgeSources"));
    }

    @Test
    void productCountErrorReportsActualAndMaximumInsteadOfBeingACitationFailure() {
        for (boolean deep : new boolean[] {true, false}) {
            int maximum = ProductComparisonValidator.limits(deep).get("maxProducts");
            var output = output(ORIGINAL, "K1");
            var products = output.withArray("products");
            JsonNode product = products.get(0).deepCopy();
            while (products.size() <= maximum) products.add(product.deepCopy());
            var failure = assertThrows(ProductComparisonValidator.ConstraintFailure.class,
                    () -> ProductComparisonValidator.validate(output, Map.of(), deep));
            assertEquals("/products", failure.issue().path());
            assertEquals("MAX_ITEMS_EXCEEDED", failure.issue().reason());
            assertEquals(maximum + 1, failure.issue().actualItems());
            assertEquals(maximum, failure.issue().maxItems());
        }
    }

    @Test
    void comparisonArrayOverflowAndWrongTypesHavePrecisePaths() {
        for (String field : new String[] {"strengths", "limitations", "dimensions"}) {
            int maximum = field.equals("dimensions") ? 8 : 6;
            var output = output(ORIGINAL, "K1");
            var comparison = (ObjectNode) output.at("/products/0/comparison");
            var items = comparison.putArray(field);
            for (int index = 0; index <= maximum; index++) items.addObject();
            var failure = assertThrows(ProductComparisonValidator.ConstraintFailure.class,
                    () -> ProductComparisonValidator.validate(output, Map.of("SKU-001", evidence("K1", "SKU-001", ORIGINAL)), true));
            assertEquals("/products/0/comparison/" + field, failure.issue().path());
            assertEquals(maximum + 1, failure.issue().actualItems());
            assertEquals(maximum, failure.issue().maxItems());
            comparison.put(field, "not an array");
            var shape = assertThrows(ProductComparisonValidator.ConstraintFailure.class,
                    () -> ProductComparisonValidator.validate(output, Map.of("SKU-001", evidence("K1", "SKU-001", ORIGINAL)), true));
            assertEquals("EXPECTED_ARRAY", shape.issue().reason());
        }
    }
}
