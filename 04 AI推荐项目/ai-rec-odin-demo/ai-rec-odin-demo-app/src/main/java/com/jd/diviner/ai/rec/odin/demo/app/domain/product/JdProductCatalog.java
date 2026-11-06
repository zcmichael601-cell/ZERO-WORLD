package com.jd.diviner.ai.rec.odin.demo.app.domain.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

public final class JdProductCatalog {

    public static final int MAX_PRODUCTS = 2000;
    public static final int MAX_CATALOG_BYTES = 32 * 1024 * 1024;

    private final List<JsonNode> products;
    private final Clock clock;
    private final Duration maxAge;

    public JdProductCatalog(ObjectMapper mapper, Resource resource, Clock clock, Duration maxAge) {
        if (maxAge.isNegative() || maxAge.isZero() || maxAge.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("商品快照有效期必须大于 0 且不超过 365 天");
        }
        this.clock = clock;
        this.maxAge = maxAge;
        try (var stream = resource.getInputStream()) {
            byte[] bytes = stream.readNBytes(MAX_CATALOG_BYTES + 1);
            if (bytes.length > MAX_CATALOG_BYTES) throw new IllegalArgumentException("商品目录不得超过 32 MB");
            JsonNode root = mapper.readTree(bytes);
            if (root == null || root.path("schemaVersion").asInt() != 1
                    || !"JD_PAGE_SNAPSHOT".equals(root.path("source").asText())
                    || !root.path("products").isArray() || root.path("products").isEmpty()
                    || root.path("products").size() > MAX_PRODUCTS) {
                throw new IllegalArgumentException("需要包含 1~" + MAX_PRODUCTS + " 件京东页面快照的商品目录");
            }
            List<JsonNode> loaded = new ArrayList<>();
            var ids = new HashSet<String>();
            for (JsonNode product : root.path("products")) {
                String id = text(product, "id", 20);
                if (!id.matches("[1-9][0-9]{5,19}") || !ids.add(id)
                        || !text(product, "sourceUrl", 100).equals("https://item.jd.com/" + id + ".html")) {
                    throw new IllegalArgumentException("商品必须具有不重复的真实数字 SKU 及对应京东商详链接");
                }
                text(product, "name", 500);
                text(product, "category", 100);
                Instant captured = Instant.parse(text(product, "capturedAt", 50));
                if (captured.isAfter(clock.instant().plusSeconds(300))) throw new IllegalArgumentException("商品采集时间不能在未来");
                if (!product.has("price") || (!product.path("price").isNull()
                        && (!product.path("price").isNumber() || !Double.isFinite(product.path("price").asDouble())
                        || product.path("price").decimalValue().signum() <= 0))) {
                    throw new IllegalArgumentException("未知商品价格必须为 null，已知价格必须为正数");
                }
                if (!List.of("AVAILABLE", "UNAVAILABLE", "UNKNOWN").contains(product.path("availability").asText())
                        || !"PAGE_SNAPSHOT".equals(product.path("priceType").asText())
                        || !"CNY".equals(product.path("currency").asText())) {
                    throw new IllegalArgumentException("商品库存状态或价格来源无效");
                }
                if (!product.path("tags").isArray() || product.path("tags").isEmpty() || product.path("tags").size() > 12) {
                    throw new IllegalArgumentException("商品必须包含 1~12 个检索标签");
                }
                for (JsonNode tag : product.path("tags")) {
                    if (!tag.isTextual() || tag.asText().isBlank() || tag.asText().length() > 50) throw new IllegalArgumentException("检索标签无效");
                }
                for (String field : List.of("selectedOptions", "specifications", "services")) {
                    if (!product.path(field).isTextual() || product.path(field).asText().length() > 6000) throw new IllegalArgumentException("商品原文字段无效：" + field);
                }
                if (knowledge(product).getBytes(StandardCharsets.UTF_8).length > 16384) throw new IllegalArgumentException("商品知识不得超过 16 KB");
                var snapshot = product.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot).remove("knowledgeText");
                loaded.add(snapshot);
            }
            products = List.copyOf(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取本地京东商品目录；不会回退到示例商品", exception);
        }
    }

    private static String text(JsonNode node, String field, int maxLength) {
        if (!node.path(field).isTextual() || node.path(field).asText().isBlank() || node.path(field).asText().length() > maxLength) {
            throw new IllegalArgumentException("商品目录缺少有效字段：" + field);
        }
        return node.path(field).asText();
    }

    public List<String> matchingInterests(String context) {
        String normalized = context.toLowerCase(java.util.Locale.ROOT);
        return products.stream().flatMap(product -> java.util.stream.StreamSupport.stream(product.path("tags").spliterator(), false))
                .map(JsonNode::asText).distinct()
                .filter(tag -> normalized.contains(tag.toLowerCase(java.util.Locale.ROOT)))
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .limit(12).toList();
    }

    public List<JsonNode> search(List<String> interests, BigDecimal budget, int limit) {
        return products.stream().filter(product -> eligible(product, budget))
                .filter(product -> matches(product, interests) > 0)
                .sorted(Comparator.<JsonNode>comparingLong(product -> matches(product, interests)).reversed()
                        .thenComparing(product -> product.path("price").decimalValue())
                        .thenComparing(product -> product.path("id").asText()))
                .limit(limit).<JsonNode>map(JsonNode::deepCopy).toList();
    }

    public List<JsonNode> eligibleProducts(BigDecimal budget) {
        return products.stream().filter(product -> eligible(product, budget)).<JsonNode>map(JsonNode::deepCopy).toList();
    }

    private boolean eligible(JsonNode product, BigDecimal budget) {
        Instant captured = Instant.parse(product.path("capturedAt").asText());
        return !captured.isBefore(clock.instant().minus(maxAge)) && !captured.isAfter(clock.instant().plusSeconds(300))
                && product.path("price").isNumber() && (budget == null || product.path("price").decimalValue().compareTo(budget) <= 0)
                && !"UNAVAILABLE".equals(product.path("availability").asText());
    }

    private static long matches(JsonNode product, List<String> interests) {
        return java.util.stream.StreamSupport.stream(product.path("tags").spliterator(), false)
                .filter(tag -> interests.contains(tag.asText())).count();
    }

    public JsonNode find(String id) {
        return products.stream().filter(product -> id.equals(product.path("id").asText()))
                .findFirst().<JsonNode>map(JsonNode::deepCopy).orElse(null);
    }

    public long maxAgeHours() { return maxAge.toHours(); }

    public Resource[] documents() {
        return products.stream().map(product -> new ByteArrayResource(knowledge(product).getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() { return product.path("id").asText() + ".md"; }
        }).toArray(Resource[]::new);
    }

    private static String knowledge(JsonNode product) {
        return "# " + product.path("name").asText() + "（京东 SKU " + product.path("id").asText() + "）\n\n"
                + "来源：" + product.path("sourceUrl").asText() + "\n采集时间：" + product.path("capturedAt").asText()
                + "\n来源类型：京东商详页公开可见内容快照；页面宣传不是独立测评。\n\n"
                + "## 页面商品标题\n" + product.path("name").asText() + "\n\n"
                + "## 当前选择的规格\n" + orUnknown(product, "selectedOptions") + "\n\n"
                + "## 商品参数原文\n" + orUnknown(product, "specifications") + "\n\n"
                + "## 页面服务说明\n" + orUnknown(product, "services") + "\n\n"
                + "## 证据边界\n本资料仅适用于上述 SKU 和已选规格。页面功能宣传不等于独立测试结果。"
                + "未取得独立测评或完整使用限制，不得编造缺点、兼容性、舒适度或性能排名。"
                + "推荐用途属于适配判断；缺失参数属于未知，不等于商品缺陷。"
                + "价格、优惠、配送与库存会变化，不作为知识库实时事实，下单前以京东商详为准。\n";
    }

    private static String orUnknown(JsonNode product, String field) {
        return product.path(field).asText().isBlank() ? "未取得可提取原文，需到京东商详核实。" : product.path(field).asText();
    }
}
