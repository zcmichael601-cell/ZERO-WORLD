package com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class ProductComparisonValidator {

    private ProductComparisonValidator() {}

    public static void resolveQuoteSelections(ObjectNode output, Map<String, JsonNode> knowledge) {
        List<CitationIssue> issues = new ArrayList<>();
        if (!output.path("products").isArray()) return;
        int productIndex = 0;
        for (JsonNode product : output.path("products")) {
            String productId = product.path("productId").asText();
            JsonNode retrieved = knowledge.get(productId);
            for (String field : List.of("strengths", "limitations", "dimensions")) {
                JsonNode claims = product.path("comparison").path(field);
                if (!claims.isArray()) continue;
                int claimIndex = 0;
                for (JsonNode claim : claims) {
                    String path = "/products/" + productIndex + "/comparison/" + field + "/" + claimIndex++;
                    if (!claim.has("quoteId")) continue;
                    JsonNode selected = null;
                    JsonNode evidence = null;
                    if (retrieved != null && claim.path("quoteId").isTextual() && claim.path("evidenceId").isTextual()) {
                        for (JsonNode source : retrieved.path("evidence")) {
                            if (!productId.equals(source.path("productId").asText())
                                    || !source.path("id").equals(claim.path("evidenceId"))) continue;
                            evidence = source;
                            for (JsonNode choice : source.path("quoteChoices")) {
                                if (choice.path("id").equals(claim.path("quoteId"))) selected = choice;
                            }
                        }
                    }
                    String text = selected == null ? "" : selected.path("text").asText();
                    String reason = selected == null ? "QUOTE_CHOICE_NOT_FOUND"
                            : text.length() < 6 || text.length() > 500 || !evidence.path("text").asText().contains(text) ? "QUOTE_NOT_IN_SOURCE"
                            : claim.has("quote") && (!claim.path("quote").isTextual() || !text.equals(claim.path("quote").asText()))
                            ? "QUOTE_SELECTION_CONFLICT" : null;
                    if (reason != null) issues.add(new CitationIssue(path, productId, reason, claim.path("quote").asText().length()));
                    else ((ObjectNode) claim).put("quote", text);
                }
            }
            productIndex++;
        }
        if (!issues.isEmpty()) throw new CitationFailure(issues);
    }

    public static Map<String, Integer> limits(boolean deep) {
        return Map.of("maxProducts", deep ? 6 : 4, "maxStrengths", deep ? 6 : 3,
                "maxLimitations", deep ? 6 : 3, "maxDimensions", deep ? 8 : 0);
    }

    public static void validate(ObjectNode output, Map<String, JsonNode> knowledge) {
        validate(output, knowledge, false);
    }

    public static void validate(ObjectNode output, Map<String, JsonNode> knowledge, boolean deep) {
        if (deep) resolveQuoteSelections(output, knowledge);
        requireText(output, "comparisonSummary");
        int maxProducts = limits(deep).get("maxProducts");
        requireArray(output.path("products"), "/products", maxProducts);
        Map<String, JsonNode> used = new LinkedHashMap<>();
        List<CitationIssue> issues = new ArrayList<>();
        int productIndex = 0;
        for (JsonNode product : output.path("products")) {
            String productId = product.path("productId").asText();
            JsonNode retrieved = knowledge.get(productId);
            if (retrieved == null) throw new IllegalArgumentException("推荐商品必须先检索商品知识");
            JsonNode comparison = product.path("comparison");
            requireText(comparison, "status", "fit", "uncertainty");
            boolean supported = "SUPPORTED".equals(comparison.path("status").asText());
            boolean partial = "PARTIAL_EVIDENCE".equals(comparison.path("status").asText());
            if (!supported && !partial && !"INSUFFICIENT_EVIDENCE".equals(comparison.path("status").asText())) {
                throw new IllegalArgumentException("商品对比状态无效");
            }
            for (String field : deep ? new String[] {"strengths", "limitations", "dimensions"} : new String[] {"strengths", "limitations"}) {
                JsonNode claims = comparison.path(field);
                int maximum = limits(deep).get(switch (field) {
                    case "strengths" -> "maxStrengths";
                    case "limitations" -> "maxLimitations";
                    default -> "maxDimensions";
                });
                requireArray(claims, "/products/" + productIndex + "/comparison/" + field, maximum);
                if ((supported && claims.isEmpty()) || (!supported && !partial && !claims.isEmpty())) {
                    throw new IllegalArgumentException("有依据的对比需同时提供优点与限制；资料不足时不得生成优缺点");
                }
                int claimIndex = 0;
                for (JsonNode claim : claims) {
                    requireText(claim, "text", "evidenceId", "quote");
                    if ("dimensions".equals(field)) requireText(claim, "dimension");
                    JsonNode evidence = null;
                    for (JsonNode candidate : retrieved.path("evidence")) {
                        if (candidate.path("id").asText().equals(claim.path("evidenceId").asText())) evidence = candidate;
                    }
                    String quote = claim.path("quote").asText();
                    String path = "/products/" + productIndex + "/comparison/" + field + "/" + claimIndex++;
                    boolean otherProduct = evidence == null && knowledge.values().stream().anyMatch(known -> {
                        for (JsonNode candidate : known.path("evidence")) {
                            if (candidate.path("id").asText().equals(claim.path("evidenceId").asText())) return true;
                        }
                        return false;
                    });
                    String reason = evidence == null ? (otherProduct ? "PRODUCT_MISMATCH" : "EVIDENCE_NOT_FOUND")
                            : !productId.equals(evidence.path("productId").asText()) ? "PRODUCT_MISMATCH"
                            : quote.length() < 6 || quote.length() > 500 ? "QUOTE_LENGTH_INVALID" : null;
                    String original = reason == null ? originalQuote(evidence.path("text").asText(), quote) : null;
                    if (reason == null && original == null) reason = "QUOTE_NOT_IN_SOURCE";
                    if (reason != null) {
                        issues.add(new CitationIssue(path, productId, reason, quote.length()));
                        continue;
                    }
                    if (!original.equals(quote)) {
                        ((ObjectNode) claim).put("quote", original);
                        ExecutionLog.current().info("PRODUCT_CITATION_WHITESPACE_RESTORED", "path", path,
                                "productId", productId, "quoteChars", quote.length(), "sourceQuoteChars", original.length());
                    }
                    used.put(evidence.path("id").asText(), evidence);
                }
            }
            if (partial && comparison.path("strengths").isEmpty() && comparison.path("limitations").isEmpty()
                    && (!deep || comparison.path("dimensions").isEmpty())) {
                throw new IllegalArgumentException("部分证据状态需至少一项有引用的结论");
            }
            productIndex++;
        }
        if (!issues.isEmpty()) throw new CitationFailure(issues);
        var sources = output.putArray("knowledgeSources");
        used.values().forEach(source -> sources.add(source.deepCopy()));
    }

    private static void requireArray(JsonNode value, String path, int maximum) {
        if (!value.isArray()) throw new ConstraintFailure(new ConstraintIssue(path, "EXPECTED_ARRAY", null, maximum));
        if (value.size() > maximum) throw new ConstraintFailure(new ConstraintIssue(path, "MAX_ITEMS_EXCEEDED", value.size(), maximum));
    }

    public record ConstraintIssue(String path, String reason, Integer actualItems, int maxItems) {}

    public static final class ConstraintFailure extends IllegalArgumentException {
        private final ConstraintIssue issue;

        public ConstraintFailure(ConstraintIssue issue) {
            super("商品对比结果不符合数量或数组约束：" + issue.path() + "，" + issue.reason()
                    + "，实际 " + issue.actualItems() + "，最多 " + issue.maxItems());
            this.issue = issue;
        }

        public ConstraintIssue issue() { return issue; }
    }

    private static String originalQuote(String text, String quote) {
        if (text.contains(quote)) return quote;
        String normalized = quote.replaceAll("[\\s\\p{Z}]+", " ").strip();
        if (normalized.length() < 6) return null;
        String pattern = java.util.Arrays.stream(normalized.split(" ")).map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("[\\s\\p{Z}]+"));
        var match = Pattern.compile(pattern).matcher(text);
        if (!match.find() || match.end() - match.start() > 500) return null;
        return text.substring(match.start(), match.end());
    }

    public record CitationIssue(String path, String productId, String reason, int quoteChars) {}

    public static final class CitationFailure extends IllegalArgumentException {
        private final List<CitationIssue> issues;

        public CitationFailure(List<CitationIssue> issues) {
            super("商品对比引用校验未通过（" + issues.size() + "处，首处 " + issues.get(0).path()
                    + "，" + issues.get(0).reason() + "），未发布未通过校验的推荐");
            this.issues = List.copyOf(issues);
        }

        public List<CitationIssue> issues() { return issues; }
    }

    private static void requireText(JsonNode node, String... fields) {
        for (String field : fields) {
            if (!node.path(field).isTextual() || node.path(field).asText().isBlank() || node.path(field).asText().length() > 1500) {
                throw new IllegalArgumentException("商品对比缺少有效文本字段：" + field);
            }
        }
    }
}
