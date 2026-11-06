package com.jd.diviner.ai.rec.odin.demo.app.application.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;

final class RecommendationLoopPolicy {
    private final SkillContext context;
    private final Map<String, List<JsonNode>> executed = new LinkedHashMap<>();
    private final Map<String, JsonNode> pending = new LinkedHashMap<>();

    RecommendationLoopPolicy(SkillContext context) { this.context = context; }

    List<String> available(List<String> names, int remainingTools, int remainingRounds) {
        Set<String> completed = context.toolResults().keySet();
        return names.stream().filter(name -> {
            if (executed.getOrDefault(name, List.of()).size() >= limit(name)) return false;
            int missing = (int) context.skill().requiredTools().stream().filter(tool -> !completed.contains(tool)).count();
            if (completed.contains(name) && (remainingTools <= missing || remainingRounds <= missing + 1)) return false;
            if (remainingRounds <= 1 && missing == 0) return false;
            if ("search_products".equals(name)) return completed.containsAll(List.of("get_user_profile", "get_user_behaviors"));
            if ("retrieve_product_knowledge".equals(name)) return completed.contains("search_products");
            return true;
        }).toList();
    }

    String review(List<AssistantMessage.ToolCall> calls, int remainingTools, int remainingRounds) {
        List<String> available = available(context.skill().tools(), remainingTools, remainingRounds);
        Set<String> completed = new HashSet<>(context.toolResults().keySet());
        Map<String, List<JsonNode>> proposed = new LinkedHashMap<>();
        executed.forEach((name, arguments) -> proposed.put(name, new ArrayList<>(arguments)));
        for (var call : calls) {
            if (!context.skill().tools().contains(call.name()) || !"function".equals(call.type())
                    || call.id() == null || call.id().isBlank() || call.arguments() == null || call.arguments().length() > 16384) return null;
            JsonNode arguments;
            try {
                arguments = context.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(call.arguments());
            } catch (java.io.IOException exception) { return null; }
            if (arguments == null || !arguments.isObject()) return null;
            List<JsonNode> previous = proposed.computeIfAbsent(call.name(), ignored -> new ArrayList<>());
            if (!available.contains(call.name()) || previous.size() >= limit(call.name())) {
                return guidance("工具已完成、次数达到上限或依赖尚未就绪", available);
            }
            if (previous.contains(arguments)) return guidance("检测到参数完全相同的重复工具调用", available);
            previous.add(arguments);
            completed.add(call.name());
        }
        long missing = context.skill().requiredTools().stream().filter(name -> !completed.contains(name)).count();
        if (calls.size() + missing > remainingTools) return guidance("须为尚未完成的必需工具预留调用预算", available);
        return null;
    }

    void started(String name, JsonNode arguments) { pending.put(name, arguments.deepCopy()); }
    void completed(String name) {
        JsonNode arguments = pending.remove(name);
        if (arguments != null) executed.computeIfAbsent(name, ignored -> new ArrayList<>()).add(arguments);
    }

    private static int limit(String name) {
        return Set.of("search_products", "retrieve_product_knowledge").contains(name) ? 2 : 1;
    }

    private static String guidance(String reason, List<String> available) {
        return reason + "；本批工具均未执行，已完成的结果保留。当前可用工具：" + available
                + "。请重新选择未重复的调用；画像和行为各至多一次，搜索及知识检索各至多两次。"
                + "已有充分依据则生成最终JSON，不必用满次数；无候选时使用空商品列表，不编造商品。";
    }
}
