package com.jd.diviner.ai.rec.odin.demo.app.domain.category;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import org.springframework.core.io.Resource;

/** Maps a brand name to the numeric brand id used by cb_brand_id. */
public final class BrandNameCatalog {

    private final Map<String, Long> codes;
    private final Map<Long, String> displays;
    private final Map<String, List<BrandCandidate>> ambiguous;

    public BrandNameCatalog(Resource resource) {
        Objects.requireNonNull(resource, "品牌映射资源不能为空");
        Map<String, Long> names = new HashMap<>();
        Map<Long, String> displayNames = new HashMap<>();
        Map<String, Map<Long, String>> aliasCodes = new HashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header != null && header.startsWith("\uFEFF")) {
                header = header.substring(1);
            }
            if (!"brand_code,brandname_cn".equals(header)) {
                throw new IllegalArgumentException("品牌映射表表头无效");
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                List<String> columns = CidTopSkuCatalog.parseCsvLine(line);
                if (columns.size() < 2) {
                    throw new IllegalArgumentException("品牌映射表列数无效: " + lineNumber);
                }
                long code = brandCode(columns.get(0).strip(), lineNumber);
                String name = columns.get(1).strip();
                if (code <= 0 || name.isEmpty() || "未知".equals(name) || "无品牌".equals(name)) {
                    continue;
                }
                String key = normalize(name);
                if (key.isEmpty()) {
                    continue;
                }
                names.merge(key, code, Math::min);
                displayNames.merge(code, name, (left, right) -> left.length() <= right.length() ? left : right);
                for (String alias : tokens(name)) {
                    if (alias.equals(key)) {
                        continue;
                    }
                    aliasCodes.computeIfAbsent(alias, ignored -> new HashMap<>())
                            .merge(code, name, (left, right) -> left.length() <= right.length() ? left : right);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取品牌映射表", exception);
        }
        aliasCodes.forEach((alias, byCode) -> {
            if (byCode.size() == 1 && !names.containsKey(alias)) {
                names.put(alias, byCode.keySet().iterator().next());
            }
        });
        Long apple = names.get(normalize("苹果"));
        if (apple != null && apple.longValue() == 14026L) {
            // 英文 apple 和中文「苹果」是同一品牌。表里另有更小的 APPLE 编号，不能让最小 ID 赢。
            names.put(normalize("Apple/苹果"), 14026L);
            names.put(normalize("Apple"), 14026L);
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("品牌映射表没有有效记录");
        }
        Map<String, List<BrandCandidate>> ambiguousTokens = new HashMap<>();
        aliasCodes.forEach((alias, byCode) -> {
            if (byCode.size() < 2 || byCode.size() > 12) {
                return;
            }
            List<BrandCandidate> list = new ArrayList<>();
            byCode.forEach((brandCode, display) -> list.add(new BrandCandidate(Long.toString(brandCode), display)));
            list.sort(Comparator.comparing(BrandCandidate::name).thenComparing(BrandCandidate::code));
            ambiguousTokens.put(alias, List.copyOf(list));
        });
        this.codes = Map.copyOf(names);
        this.displays = Map.copyOf(displayNames);
        this.ambiguous = Map.copyOf(ambiguousTokens);
    }

    /** 映射没有唯一 ID 时的候选。先用能对上的片段，没有片段时再取原文里包含的品牌名。 */
    public List<BrandCandidate> candidates(String brand, int limit) {
        if (brand == null || brand.isBlank() || limit < 1) {
            return List.of();
        }
        String text = brand.strip();
        if (text.matches("[1-9]\\d{0,18}")) {
            return List.of();
        }
        String normalized = normalize(text);
        if (codes.containsKey(normalized)) {
            return List.of();
        }
        Map<Long, BrandCandidate> found = new LinkedHashMap<>();
        for (String token : tokens(text)) {
            addCandidate(found, codes.get(token), token);
            for (BrandCandidate extra : ambiguous.getOrDefault(token, List.of())) {
                found.putIfAbsent(Long.parseLong(extra.code()), extra);
            }
            if (found.size() >= limit) {
                break;
            }
        }
        if (found.isEmpty() && normalized.length() >= 2) {
            addContainedNames(found, normalized, limit);
        }
        if (found.size() <= limit) {
            return List.copyOf(found.values());
        }
        return List.copyOf(new ArrayList<>(found.values()).subList(0, limit));
    }

    public record BrandCandidate(String code, String name) {
    }

    /**
     * 数字字符串原样当作品牌 ID。名称先整段匹配，整段命中就用这一条。
     * 整段没有时再按斜杠和括号拆开匹配。同名保留更小的品牌 ID。
     * 拆开后落到多个不同 ID 时不猜测。苹果的 14026 同时登记为 Apple/苹果，并把单独的 apple 修到这一条。
     */
    public Optional<String> codeOf(String brand) {
        if (brand == null || brand.isBlank()) {
            return Optional.empty();
        }
        String text = brand.strip();
        if (text.matches("[1-9]\\d{0,18}")) {
            return Optional.of(text);
        }
        Long direct = codes.get(normalize(text));
        if (direct != null) {
            return Optional.of(Long.toString(direct));
        }
        Set<Long> found = new LinkedHashSet<>();
        for (String token : tokens(text)) {
            Long code = codes.get(token);
            if (code != null) {
                found.add(code);
            }
        }
        if (found.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(Long.toString(found.iterator().next()));
    }

    private void addCandidate(Map<Long, BrandCandidate> found, Long code, String fallbackName) {
        if (code == null || code <= 0 || found.containsKey(code)) {
            return;
        }
        found.put(code, new BrandCandidate(Long.toString(code), displays.getOrDefault(code, fallbackName)));
    }

    private void addContainedNames(Map<Long, BrandCandidate> found, String normalized, int limit) {
        PriorityQueue<FuzzyHit> longest = new PriorityQueue<>(Comparator.comparingInt(FuzzyHit::length));
        for (var entry : codes.entrySet()) {
            String key = entry.getKey();
            if (key.length() < 2 || key.length() > normalized.length() || !normalized.contains(key)
                    || found.containsKey(entry.getValue())) {
                continue;
            }
            longest.add(new FuzzyHit(key.length(), entry.getValue(), key));
            if (longest.size() > limit) {
                longest.poll();
            }
        }
        List<FuzzyHit> ranked = new ArrayList<>(longest);
        ranked.sort(Comparator.comparingInt(FuzzyHit::length).reversed().thenComparingLong(FuzzyHit::code));
        for (FuzzyHit hit : ranked) {
            if (found.size() >= limit) {
                break;
            }
            addCandidate(found, hit.code, hit.key);
        }
    }

    private record FuzzyHit(int length, long code, String key) {
    }

    private static long brandCode(String raw, int lineNumber) {
        if (!raw.matches("-?\\d+")) {
            throw new IllegalArgumentException("品牌 ID 无效: " + lineNumber);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("品牌 ID 无效: " + lineNumber, exception);
        }
    }

    static String normalize(String raw) {
        return raw.strip().toLowerCase(Locale.ROOT)
                .replace('（', '(')
                .replace('）', ')')
                .replace('／', '/')
                .replaceAll("\\s+", "");
    }

    private static List<String> tokens(String raw) {
        String normalized = normalize(raw);
        List<String> tokens = new ArrayList<>();
        for (String part : normalized.split("[/|()]+")) {
            if (part.length() >= 2) {
                tokens.add(part);
            }
        }
        return tokens;
    }
}
