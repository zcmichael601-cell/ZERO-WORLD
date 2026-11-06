package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation.RecommendationOutputContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationContractTests {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode valid() throws Exception {
        ObjectNode output = (ObjectNode) mapper.readTree(SkillEngineTests.FINAL);
        ((ObjectNode) output.at("/products/0/comparison")).putArray("dimensions");
        return output;
    }

    @Test
    void acceptsInsufficientEvidenceAndEmptyArraysWithoutInventingFacts() throws Exception {
        assertTrue(RecommendationOutputContract.inspect(valid(), RecommendationOutputContract.schema(mapper)).isEmpty());
        var empty = valid();
        empty.putArray("products");
        empty.putArray("needs");
        assertTrue(RecommendationOutputContract.inspect(empty, RecommendationOutputContract.schema(mapper)).isEmpty());
    }

    @Test
    void collectsMultipleRecoverableShapeErrorsWithoutLeakingGeneratedValues() throws Exception {
        var output = valid();
        output.put("needs", "private-user-memory");
        ((ObjectNode) output.at("/products/0")).put("score", "85%");
        ((ObjectNode) output.at("/products/0/comparison")).remove("dimensions");
        var issues = RecommendationOutputContract.inspect(output, RecommendationOutputContract.schema(mapper));
        assertEquals(3, issues.size());
        assertTrue(issues.stream().anyMatch(issue -> issue.path().equals("/needs") && issue.reason().equals("EXPECTED_ARRAY")));
        assertFalse(issues.toString().contains("private-user-memory"));
    }

    @Test
    void detectsEvidenceStatusConflictsRatherThanForcingInventedLimitations() throws Exception {
        for (String status : new String[] {"SUPPORTED", "PARTIAL_EVIDENCE", "UNKNOWN"}) {
            var output = valid();
            ((ObjectNode) output.at("/products/0/comparison")).put("status", status);
            var issues = RecommendationOutputContract.inspect(output, RecommendationOutputContract.schema(mapper));
            assertEquals(1, issues.size());
            assertTrue(issues.get(0).path().endsWith("/status"));
        }
    }

    @Test
    void reportsIndependentShapeAndEvidenceStatusProblemsInTheSamePass() throws Exception {
        var output = valid();
        output.put("needs", "invalid-type");
        ((ObjectNode) output.at("/products/0/comparison")).put("status", "SUPPORTED");
        var issues = RecommendationOutputContract.inspect(output, RecommendationOutputContract.schema(mapper));
        assertEquals(2, issues.size());
        assertTrue(issues.stream().anyMatch(issue -> "EVIDENCE_STATUS_CONFLICT".equals(issue.reason())));
    }

    @Test
    void enforcesIntegerScoresAndBoundsDiagnosticSize() throws Exception {
        var output = valid();
        ((ObjectNode) output.at("/needs/0")).put("confidence", 101);
        ((ObjectNode) output.at("/products/0")).put("score", 90.5);
        assertEquals(2, RecommendationOutputContract.inspect(output, RecommendationOutputContract.schema(mapper)).size());
        var products = output.putArray("products");
        for (int index = 0; index < 200; index++) products.addObject();
        assertTrue(RecommendationOutputContract.inspect(output, RecommendationOutputContract.schema(mapper)).size() <= 32);
    }
}
