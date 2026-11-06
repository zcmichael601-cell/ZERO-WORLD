package com.jd.diviner.ai.rec.odin.demo.app.application.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillExecutionException;

final class FastRecommendationResult {
    private FastRecommendationResult() {}

    static void complete(ObjectNode output, int candidateCount, boolean userDataMissing) {
        int selectedCount = output.path("products").size();
        if (selectedCount > 2) throw new SkillExecutionException("极速推荐最多支持 2 件商品");
        output.put("candidateCount", candidateCount);
        output.remove("emptyReason");
        if (selectedCount > 0) {
            output.put("selectionStatus", "RECOMMENDED");
            output.put("summary", "为你筛选了" + selectedCount + "件商品；价格与库存以京东页面为准");
            for (JsonNode product : output.path("products")) {
                ((ObjectNode) product).put("selectionSource", "MODEL");
            }
            output.remove("selectionExplanation");
        } else if (userDataMissing) {
            output.put("selectionStatus", "INSUFFICIENT_USER_INFORMATION");
            output.put("summary", "用户资料不足，暂无法个性化推荐；价格与库存以京东页面为准");
            output.put("emptyReason", "本次检索返回" + candidateCount + "件候选商品，但画像和行为资料均为空，请补充需求后重试。");
            output.remove("selectionExplanation");
        } else if (candidateCount == 0) {
            output.put("selectionStatus", "NO_CANDIDATES");
            output.put("summary", "本次检索暂无候选商品；价格与库存以京东页面为准");
            output.put("emptyReason", "本次检索没有返回候选商品，可调整兴趣、预算或更新商品目录后重试。");
            output.remove("selectionExplanation");
        } else {
            throw new SkillExecutionException("极速推荐有候选时必须生成商品结果");
        }
    }
}
