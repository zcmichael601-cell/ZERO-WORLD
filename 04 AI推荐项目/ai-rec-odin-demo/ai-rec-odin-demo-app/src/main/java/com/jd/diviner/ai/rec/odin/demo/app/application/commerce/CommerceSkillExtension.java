package com.jd.diviner.ai.rec.odin.demo.app.application.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillDefinition;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillBehavior;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillContext;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillExtension;
import com.jd.diviner.ai.rec.odin.demo.app.domain.user.UserIdentifier;
import com.jd.diviner.ai.rec.odin.demo.app.tools.user.DemoUserTools;
import java.util.List;
import java.util.Set;

public final class CommerceSkillExtension implements SkillExtension {
    public String name() { return "commerce"; }
    public SkillBehavior create(SkillContext context) { return new CommerceSkillBehavior(context); }

    public boolean supportsLegacy(SkillDefinition skill) {
        return Set.of("shopping-recommendation", "shopping-recommendation-fast", "shopping-recommendation-deep",
                "user-insight", "shopping-behavior-prediction", "beijing-weather-travel").contains(skill.id())
                || skill.preparedRecommendation() || skill.deepWorkflow();
    }

    public boolean supportsMode(String mode) {
        return mode == null || List.of("AGENT", "PREPARED_RECOMMENDATION", "DEEP_RECOMMENDATION_WORKFLOW").contains(mode);
    }

    public int minimumModelCalls(SkillDefinition skill) { return skill.preparedRecommendation() ? 1 : 2; }

    public void validateDefinition(SkillDefinition skill) {
        if (skill.deepWorkflow() && (!skill.deepRecommendation()
                || !skill.tools().equals(List.of("get_user_profile", "get_user_behaviors", "search_products", "retrieve_product_knowledge"))
                || !skill.requiredTools().equals(skill.tools()) || !skill.requiredInputs().equals(List.of("username"))
                || skill.maxModelCalls() < 3 || skill.maxToolCalls() < 5)) {
            throw new IllegalArgumentException("满血版准备流程需配置完整画像、行为、召回与知识工具，至少3轮模型和5次工具调用");
        }
        if (skill.preparedRecommendation() && (skill.maxModelCalls() != 2 || skill.maxToolCalls() != 4
                || !skill.tools().equals(List.of("get_user_profile", "get_user_behaviors", "search_products"))
                || !skill.requiredTools().equals(skill.tools())
                || !skill.requiredInputs().equals(List.of("username")))) {
            throw new IllegalArgumentException("极速推荐需配置画像、行为、商品三个工具及两次模型调用上限（正常一次、修正一次）、四次工具调用（含 Skill 加载）");
        }
    }

    public void validateInput(SkillDefinition skill, JsonNode input) {
        if (input.has("userDataMode")) {
            String mode = input.path("userDataMode").asText();
            if (!input.path("userDataMode").isTextual() || !Set.of("REAL", "DEMO").contains(mode)
                    || !skill.supportsUserDataMode()) {
                throw new IllegalArgumentException("仅个性化好物推荐支持 userDataMode=REAL 或 DEMO");
            }
            if ("DEMO".equals(mode) && DemoUserTools.users().stream()
                    .noneMatch(user -> user.username().equals(input.path("username").asText()))) {
                throw new IllegalArgumentException("请选择有效的演示用户");
            }
        }
        if (input.has("username") && (!input.path("username").isTextual()
                || !UserIdentifier.validate(input.path("username").asText()).equals(input.path("username").asText()))) {
            throw new IllegalArgumentException("username 必须为有效且无首尾空白的用户标识；真实用户请填写 UUID");
        }
        if ("beijing-weather-travel".equals(skill.id())) {
            JsonNode interests = input.path("interests");
            if (!interests.isTextual() || interests.asText().isBlank() || interests.asText().length() > 512
                    || interests.asText().chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("出游兴趣必须为 1~512 个字符，且不能包含控制字符");
            }
        }
    }

    public void validateUserDataMode(SkillDefinition skill, String mode) {
        if (!Set.of("REAL", "DEMO").contains(mode) || ("DEMO".equals(mode) && !skill.supportsUserDataMode())) {
            throw new IllegalArgumentException("用户来源无效");
        }
    }
}
