package com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns raw AI Memory lines into the plain-text shopping intent that OpenJEV receives as state. */
public final class UserIntentPortrait {
    private static final int MAX_MEMORIES = 12;
    private static final int MAX_MEMORY_CHARS = 180;
    private static final int MAX_FACTS_CHARS = 4000;
    private static final int MIN_STATE_CHARS = 12;
    private static final int MAX_STATE_CHARS = 800;
    private static final Pattern CATEGORY_LINE = Pattern.compile("类目\\s*[:：]\\s*([^\\n]+)");

    private UserIntentPortrait() {}

    public static String facts(JsonNode profile, JsonNode behaviors) {
        StringBuilder text = new StringBuilder();
        append(text, "长期画像", profile);
        appendCounts(text, behaviors);
        String facts = text.toString().strip();
        return facts.length() <= MAX_FACTS_CHARS ? facts : facts.substring(0, MAX_FACTS_CHARS);
    }

    /** Same action plus sku, or the exact memory text when sku is absent. Counts are record counts. */
    public static ObjectNode countedBehaviors(JsonNode behaviors, ObjectMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        if (behaviors != null) {
            for (String field : List.of("username", "source", "dataStatus")) {
                if (behaviors.has(field)) result.set(field, behaviors.get(field).deepCopy());
            }
        }
        result.put("countMethod", "相同行为类型下，有 sku 按 sku 计数，否则按完全相同原文计数");
        ArrayNode counts = result.putArray("counts");
        for (Tally item : tally(behaviors)) {
            ObjectNode row = counts.addObject().put("text", item.label).put("count", item.count);
            if (!item.action.isBlank()) row.put("action", item.action);
            if (!item.sku.isBlank()) row.put("skuId", item.sku);
        }
        return result;
    }

    public static List<String> categories(String modelText, List<String> allowed) {
        if (modelText == null || allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        Matcher matcher = CATEGORY_LINE.matcher(modelText);
        if (!matcher.find()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (String part : matcher.group(1).split("[、,，]")) {
            String name = part.strip().replaceAll("[。.;；]+$", "");
            if (allowed.contains(name) && !found.contains(name)) {
                found.add(name);
            }
            if (found.size() == 6) {
                break;
            }
        }
        return List.copyOf(found);
    }

    /** Parses `类目: 一级、一级` into 2 or 3 distinct names. */
    public static List<String> categoryChoices(String modelText) {
        return categoryNameList(modelText, 2, 3, true);
    }

    /**
     * Parses one line of category names separated by顿号/comma.
     * Accepts either `类目: a、b` or a bare `a、b` reply.
     */
    public static List<String> categoryNameList(String modelText, int min, int max, boolean rejectPath) {
        if (modelText == null) {
            throw new IllegalArgumentException("模型未给出类目");
        }
        if (min < 1 || max < min) {
            throw new IllegalArgumentException("类目数量范围无效");
        }
        String line;
        Matcher matcher = CATEGORY_LINE.matcher(modelText);
        if (matcher.find()) {
            line = matcher.group(1).strip();
        } else {
            line = modelText.lines().map(String::strip).filter(part -> !part.isEmpty()).findFirst().orElse("").strip();
        }
        if (line.isEmpty()) {
            throw new IllegalArgumentException("模型未给出类目");
        }
        if (rejectPath && (line.contains(">") || line.contains("＞"))) {
            throw new IllegalArgumentException("不要写二级或三级路径，只用顿号列出类目名");
        }
        List<String> names = new ArrayList<>();
        for (String part : line.split("[、,，]")) {
            String name = part.strip().replaceAll("[。.;；]+$", "");
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
        if (names.size() < min || names.size() > max) {
            throw new IllegalArgumentException("类目需要选择 " + min + " 到 " + max + " 个，当前为 " + names.size() + " 个");
        }
        return List.copyOf(names);
    }

    /** Parses `类目: 一级` or `类目: 一级 > 二级 > 三级`. Later parts may be absent. */
    public static List<String> categoryLevels(String modelText) {
        if (modelText == null) {
            throw new IllegalArgumentException("模型未给出类目");
        }
        Matcher matcher = CATEGORY_LINE.matcher(modelText);
        if (!matcher.find()) {
            throw new IllegalArgumentException("模型未给出类目行，需要一行「类目: 一级」");
        }
        String[] parts = matcher.group(1).strip().split("\\s*>\\s*|\\s*＞\\s*");
        List<String> levels = new ArrayList<>();
        for (String part : parts) {
            String name = part.strip().replaceAll("[。.;；]+$", "");
            if (!name.isEmpty()) {
                levels.add(name);
            }
        }
        if (levels.isEmpty() || levels.size() > 3) {
            throw new IllegalArgumentException("类目行需要一级，以及可选的二级、三级，用 > 分隔");
        }
        return List.copyOf(levels);
    }

    public static String withoutCategoryLine(String modelText) {
        if (modelText == null) {
            return "";
        }
        return CATEGORY_LINE.matcher(modelText).replaceFirst("").strip();
    }

    public static String state(String modelText, ObjectMapper mapper) {
        if (modelText == null || modelText.isBlank()) {
            throw new IllegalArgumentException("模型未生成用户意图描述");
        }
        String text = modelText.strip().replaceAll("(?s)^```(?:json|text|markdown)?\\s*", "").replaceAll("(?s)```\\s*$", "").strip();
        if (text.startsWith("{") && text.endsWith("}")) {
            try {
                JsonNode node = mapper.readTree(text);
                for (String field : List.of("state", "description", "summary", "intent")) {
                    if (node.path(field).isTextual() && !node.path(field).asText().isBlank()) {
                        text = node.path(field).asText().strip();
                        break;
                    }
                }
            } catch (Exception ignored) {
                // Keep the original text when the model did not return a portrait object.
            }
        }
        text = text.replaceAll("\\p{Cc}+", " ").replaceAll("\\s+", " ").strip();
        if (text.length() < MIN_STATE_CHARS || text.length() > MAX_STATE_CHARS) {
            throw new IllegalArgumentException("用户意图描述需为 " + MIN_STATE_CHARS + "~" + MAX_STATE_CHARS + " 字符");
        }
        return text;
    }

    private record Tally(String action, String sku, String label, int count, int order) {}

    private static List<Tally> tally(JsonNode data) {
        JsonNode memories = data == null ? null : data.path("memories");
        Map<String, Tally> groups = new LinkedHashMap<>();
        if (memories != null && memories.isArray()) {
            int order = 0;
            for (JsonNode memory : memories) {
                String label = memory.path("memory").asText("").replaceAll("\\p{Cc}+", " ").replaceAll("\\s+", " ").strip();
                if (label.isEmpty()) continue;
                if (label.length() > MAX_MEMORY_CHARS) label = label.substring(0, MAX_MEMORY_CHARS);
                String action = memory.at("/metadata/memory_subtype2").asText("").strip();
                String sku = memory.at("/metadata/skuid").asText("").strip();
                String key = action + "\0" + (sku.isEmpty() ? "text:" + label : "sku:" + sku);
                Tally existing = groups.get(key);
                if (existing == null) {
                    groups.put(key, new Tally(action, sku, label, 1, order));
                } else {
                    groups.put(key, new Tally(existing.action, existing.sku, existing.label, existing.count + 1, existing.order));
                }
                order++;
            }
        }
        return groups.values().stream()
                .sorted(Comparator.comparingInt(Tally::count).reversed().thenComparingInt(Tally::order))
                .limit(MAX_MEMORIES)
                .toList();
    }

    private static void appendCounts(StringBuilder text, JsonNode behaviors) {
        if (text.length() > 0) text.append('\n');
        text.append("近期行为（相同 sku 或完全相同原文按条数统计）：\n");
        for (Tally item : tally(behaviors)) {
            text.append("- ");
            if (!item.action.isBlank()) text.append('[').append(item.action).append("] ");
            text.append(item.label).append(" ×").append(item.count).append('\n');
        }
    }

    private static void append(StringBuilder text, String title, JsonNode data) {
        if (text.length() > 0) text.append('\n');
        text.append(title).append("：\n");
        int kept = 0;
        JsonNode memories = data == null ? null : data.path("memories");
        if (memories == null || !memories.isArray()) return;
        for (JsonNode memory : memories) {
            if (kept == MAX_MEMORIES) break;
            String line = memory.path("memory").asText("").replaceAll("\\p{Cc}+", " ").strip();
            if (line.isEmpty()) continue;
            if (line.length() > MAX_MEMORY_CHARS) line = line.substring(0, MAX_MEMORY_CHARS);
            text.append("- ").append(line).append('\n');
            kept++;
        }
    }
}
