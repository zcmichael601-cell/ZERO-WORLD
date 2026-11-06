package com.jd.diviner.ai.rec.odin.demo.app.domain.category;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.io.Resource;

public final class CidTopSkuCatalog {
    private static final Set<String> EXCLUDED_INTENT_CID1 =
            Set.of("到家服务", "卖家服务", "二手商品", "二手回收", "医药", "医疗保健");

    public static final int MAX_SKUS = 10;
    public static final int SNIFFER_SKUS_PER_CATEGORY = 3;
    private static final Set<String> BLOCKED_NAME_PARTS = Set.of("京东卡", "权益包");

    private final Map<String, Map<String, Map<String, CategoryTopSkus>>> tree;
    private final List<String> cid1Names;

    public CidTopSkuCatalog(Resource resource) {
        Objects.requireNonNull(resource, "类目热销表资源不能为空");
        Map<String, Map<String, Map<String, CategoryTopSkus>>> loaded = new LinkedHashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header != null && header.startsWith("\uFEFF")) {
                header = header.substring(1);
            }
            if (header == null || !header.contains("cid3_name") || !header.contains("top10_sku_list")) {
                throw new IllegalArgumentException("类目热销表表头无效");
            }
            String line;
            int rows = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> cols = parseCsvLine(line);
                if (cols.size() < 9) {
                    throw new IllegalArgumentException("类目热销表列数不足: " + rows);
                }
                String cid1 = cols.get(0).strip();
                String cid2 = cols.get(1).strip();
                String cid3 = cols.get(2).strip();
                if (cid1.isEmpty() || cid2.isEmpty() || cid3.isEmpty() || blocked(cid1, cid2, cid3)) {
                    continue;
                }
                List<String> skus = splitList(cols.get(7));
                List<Long> qtys = splitLongList(cols.get(8));
                if (skus.isEmpty() || skus.size() != qtys.size()) {
                    throw new IllegalArgumentException("类目热销表 SKU 与销量列不匹配: " + cid3);
                }
                if (skus.size() > MAX_SKUS) {
                    skus = skus.subList(0, MAX_SKUS);
                    qtys = qtys.subList(0, MAX_SKUS);
                }
                loaded.computeIfAbsent(cid1, key -> new LinkedHashMap<>())
                        .computeIfAbsent(cid2, key -> new LinkedHashMap<>())
                        .put(cid3, new CategoryTopSkus(cid1, cid2, cid3, List.copyOf(skus), List.copyOf(qtys)));
                rows++;
            }
            if (loaded.isEmpty()) {
                throw new IllegalArgumentException("类目热销表无有效行");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取类目热销表", exception);
        }
        this.tree = Map.copyOf(deepFreeze(loaded));
        this.cid1Names = List.copyOf(this.tree.keySet());
    }

    public List<String> cid1Names() {
        return cid1Names;
    }

    public List<String> intentCid1Names() {
        return cid1Names.stream().filter(name -> !EXCLUDED_INTENT_CID1.contains(name)).toList();
    }

    /** Compact menu of allowed cid1 and their cid2 names. cid3 is chosen after the parent is known. */
    public String intentCategoryMenu() {
        StringBuilder menu = new StringBuilder();
        for (String cid1 : intentCid1Names()) {
            if (!menu.isEmpty()) {
                menu.append('\n');
            }
            menu.append(cid1).append('：').append(String.join("、", cid2Names(cid1)));
        }
        return menu.toString();
    }

    /** Consumer categories JEV can actually separate. The full 80-way list is too flat to rank. */
    public List<String> shopperCid1Names() {
        return cid1Names.stream().filter(CidTopSkuCatalog::shopperFacing).toList();
    }

    private static boolean shopperFacing(String name) {
        if (name.startsWith("（京喜通）") || name.startsWith("【测试")) {
            return false;
        }
        return !Set.of("公益", "拍卖", "房地产", "京东金融", "卖家服务", "邮币", "二手回收", "二手商品").contains(name);
    }

    public List<String> cid2Names(String cid1) {
        Map<String, Map<String, CategoryTopSkus>> level2 = tree.get(cid1);
        return level2 == null ? List.of() : List.copyOf(level2.keySet());
    }

    public List<String> cid3Names(String cid1, String cid2) {
        Map<String, Map<String, CategoryTopSkus>> level2 = tree.get(cid1);
        if (level2 == null) {
            return List.of();
        }
        Map<String, CategoryTopSkus> level3 = level2.get(cid2);
        return level3 == null ? List.of() : List.copyOf(level3.keySet());
    }

    public CategoryTopSkus find(String cid1, String cid2, String cid3) {
        Map<String, Map<String, CategoryTopSkus>> level2 = tree.get(cid1);
        if (level2 == null) {
            return null;
        }
        Map<String, CategoryTopSkus> level3 = level2.get(cid2);
        return level3 == null ? null : level3.get(cid3);
    }

    public List<SkuSale> topSkus(String cid1, String cid2, String cid3, int limit) {
        if (limit < 1 || limit > MAX_SKUS) {
            throw new IllegalArgumentException("limit 必须在 1~" + MAX_SKUS);
        }
        CategoryTopSkus row = find(cid1, cid2, cid3);
        if (row == null) {
            return List.of();
        }
        int size = Math.min(limit, row.skuIds().size());
        List<SkuSale> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(new SkuSale(row.skuIds().get(i), row.saleQtys().get(i)));
        }
        return List.copyOf(result);
    }

    /**
     * Round-robin merge of each category's hot list into at most {@code limit} unique SKUs
     * (used as a single Sniffer seed list after the model picks 1–3 cid3s).
     */
    public List<SkuSale> mergeTopSkus(List<CategoryPath> paths, int limit) {
        return mergeTopSkus(paths, limit, MAX_SKUS);
    }

    /**
     * Round-robin merge with an explicit per-category cap.
     */
    public List<SkuSale> mergeTopSkus(List<CategoryPath> paths, int limit, int perCategoryLimit) {
        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("类目列表不能为空");
        }
        if (limit < 1 || limit > MAX_SKUS) {
            throw new IllegalArgumentException("limit 必须在 1~" + MAX_SKUS);
        }
        if (perCategoryLimit < 1 || perCategoryLimit > MAX_SKUS) {
            throw new IllegalArgumentException("perCategoryLimit 必须在 1~" + MAX_SKUS);
        }
        List<List<SkuSale>> pools = new ArrayList<>(paths.size());
        for (CategoryPath path : paths) {
            if (path == null) {
                throw new IllegalArgumentException("类目路径无效");
            }
            pools.add(topSkus(path.cid1(), path.cid2(), path.cid3(), perCategoryLimit));
        }
        List<SkuSale> merged = new ArrayList<>(limit);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int rank = 0;
        boolean progressed;
        do {
            progressed = false;
            for (List<SkuSale> pool : pools) {
                if (rank >= pool.size()) {
                    continue;
                }
                progressed = true;
                SkuSale sale = pool.get(rank);
                if (seen.add(sale.skuId())) {
                    merged.add(sale);
                    if (merged.size() == limit) {
                        return List.copyOf(merged);
                    }
                }
            }
            rank++;
        } while (progressed);
        return List.copyOf(merged);
    }

    public record CategoryPath(String cid1, String cid2, String cid3) {
        public CategoryPath {
            if (cid1 == null || cid1.isBlank() || cid2 == null || cid2.isBlank() || cid3 == null || cid3.isBlank()) {
                throw new IllegalArgumentException("类目路径不完整");
            }
        }
    }

    private static boolean blocked(String cid1, String cid2, String cid3) {
        String joined = (cid1 + "/" + cid2 + "/" + cid3).toLowerCase(Locale.ROOT);
        for (String part : BLOCKED_NAME_PARTS) {
            if (joined.contains(part.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Map<String, Map<String, CategoryTopSkus>>> deepFreeze(
            Map<String, Map<String, Map<String, CategoryTopSkus>>> source) {
        Map<String, Map<String, Map<String, CategoryTopSkus>>> copy = new LinkedHashMap<>();
        for (var e1 : source.entrySet()) {
            Map<String, Map<String, CategoryTopSkus>> l2 = new LinkedHashMap<>();
            for (var e2 : e1.getValue().entrySet()) {
                l2.put(e2.getKey(), Map.copyOf(e2.getValue()));
            }
            copy.put(e1.getKey(), Map.copyOf(l2));
        }
        return copy;
    }

    private static List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String value = raw.strip();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        List<String> values = new ArrayList<>();
        for (String part : value.split(",")) {
            String item = part.strip();
            if (!item.isEmpty()) {
                values.add(item);
            }
        }
        return values;
    }

    private static List<Long> splitLongList(String raw) {
        List<String> parts = splitList(raw);
        List<Long> values = new ArrayList<>(parts.size());
        for (String part : parts) {
            values.add(Long.parseLong(part));
        }
        return values;
    }

    static List<String> parseCsvLine(String line) {
        List<String> cols = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                cols.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cols.add(current.toString());
        return cols;
    }

    public record CategoryTopSkus(String cid1Name, String cid2Name, String cid3Name, List<String> skuIds, List<Long> saleQtys) {
    }

    public record SkuSale(String skuId, long saleQty) {
    }
}
