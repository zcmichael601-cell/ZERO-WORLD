package com.jd.diviner.ai.rec.odin.demo.app.domain.category;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.io.Resource;

/** Resolves a model-selected cid1/cid2 name pair to the numeric cid2 used by Diviner. */
public final class Cid2NameCatalog {

    private final Map<CategoryName, Entry> entries;
    private final Map<Integer, Entry> byCid2;

    public Cid2NameCatalog(Resource resource) {
        Objects.requireNonNull(resource, "cid2 映射资源不能为空");
        Map<CategoryName, Entry> loaded = new LinkedHashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header != null && header.startsWith("\uFEFF")) {
                header = header.substring(1);
            }
            if (!"cid1,cid1_name,cid2,cid2_name,n_cid3".equals(header)) {
                throw new IllegalArgumentException("cid2 映射表表头无效");
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                List<String> columns = CidTopSkuCatalog.parseCsvLine(line);
                if (columns.size() != 5) {
                    throw new IllegalArgumentException("cid2 映射表列数无效: " + lineNumber);
                }
                String cid1Name = columns.get(1).strip();
                String cid2Name = columns.get(3).strip();
                if (cid1Name.isEmpty() || cid2Name.isEmpty() || "0".equals(cid1Name) || "0".equals(cid2Name)) {
                    continue;
                }
                int cid1 = positiveInt(columns.get(0), "cid1", lineNumber);
                int cid2 = positiveInt(columns.get(2), "cid2", lineNumber);
                int cid3Count = nonNegativeInt(columns.get(4), "n_cid3", lineNumber);
                CategoryName key = new CategoryName(cid1Name, cid2Name);
                Entry candidate = new Entry(cid1, cid1Name, cid2, cid2Name, cid3Count);
                loaded.merge(key, candidate, Cid2NameCatalog::preferBroaderEntry);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 cid2 映射表", exception);
        }
        if (loaded.isEmpty()) {
            throw new IllegalArgumentException("cid2 映射表没有有效记录");
        }
        Map<Integer, Entry> indexed = new LinkedHashMap<>();
        for (Entry entry : loaded.values()) {
            indexed.merge(entry.cid2(), entry, (current, candidate) ->
                    current.cid3Count() >= candidate.cid3Count() ? current : candidate);
        }
        this.entries = Map.copyOf(loaded);
        this.byCid2 = Map.copyOf(indexed);
    }

    /**
     * Duplicate names exist in the source taxonomy. The entry covering more cid3 nodes is the
     * canonical branch and matches the category menu used by this skill.
     */
    private static Entry preferBroaderEntry(Entry current, Entry candidate) {
        if (current.cid2() == candidate.cid2()) {
            return current.cid3Count() >= candidate.cid3Count() ? current : candidate;
        }
        if (current.cid1() != candidate.cid1()) {
            throw new IllegalArgumentException(
                    "同名 cid2 映射到了不同 cid1: " + current.cid1Name() + "/" + current.cid2Name());
        }
        if (current.cid3Count() == candidate.cid3Count()) {
            throw new IllegalArgumentException(
                    "同名 cid2 无法确定主分支: " + current.cid1Name() + "/" + current.cid2Name());
        }
        return current.cid3Count() > candidate.cid3Count() ? current : candidate;
    }

    public Entry require(String cid1Name, String cid2Name) {
        Entry entry = find(cid1Name, cid2Name);
        if (entry == null) {
            throw new IllegalArgumentException("找不到 cid2 映射: " + cid1Name + "/" + cid2Name);
        }
        return entry;
    }

    public Entry find(String cid1Name, String cid2Name) {
        return entries.get(new CategoryName(normalize(cid1Name), normalize(cid2Name)));
    }

    public Entry findByCid2(int cid2) {
        return cid2 <= 0 ? null : byCid2.get(cid2);
    }

    public int size() {
        return entries.size();
    }

    private static int positiveInt(String raw, String field, int lineNumber) {
        int value = nonNegativeInt(raw, field, lineNumber);
        if (value == 0) {
            throw new IllegalArgumentException("cid2 映射表 " + field + " 必须为正整数: " + lineNumber);
        }
        return value;
    }

    private static int nonNegativeInt(String raw, String field, int lineNumber) {
        try {
            int value = Integer.parseInt(raw.strip());
            if (value < 0) {
                throw new NumberFormatException("negative");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "cid2 映射表 " + field + " 无效: " + lineNumber, exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private record CategoryName(String cid1Name, String cid2Name) {
    }

    public record Entry(int cid1, String cid1Name, int cid2, String cid2Name, int cid3Count) {
    }
}
