package com.jd.diviner.ai.rec.odin.demo.app.agent.skill;

import java.util.List;

public record SkillDefinition(String id, String name, String description, boolean enabled,
                              List<String> requiredInputs, String instructions, List<String> tools,
                              List<String> requiredTools, List<String> requiredOutputs,
                              int maxModelCalls, int maxToolCalls, String executionMode, String reasoningEffort, String handler) {

    public SkillDefinition(String id, String name, String description, boolean enabled,
                           List<String> requiredInputs, String instructions, List<String> tools,
                           List<String> requiredTools, List<String> requiredOutputs, int maxModelCalls, int maxToolCalls,
                           String executionMode, String reasoningEffort) {
        this(id, name, description, enabled, requiredInputs, instructions, tools, requiredTools,
                requiredOutputs, maxModelCalls, maxToolCalls, executionMode, reasoningEffort, null);
    }

    public SkillDefinition(String id, String name, String description, boolean enabled,
                           List<String> requiredInputs, String instructions, List<String> tools,
                           List<String> requiredTools, List<String> requiredOutputs, int maxModelCalls, int maxToolCalls) {
        this(id, name, description, enabled, requiredInputs, instructions, tools, requiredTools,
                requiredOutputs, maxModelCalls, maxToolCalls, null, null);
    }

    public boolean preparedRecommendation() {
        return "PREPARED_RECOMMENDATION".equals(executionMode);
    }

    public boolean supportsUserDataMode() {
        return "shopping-recommendation".equals(id) || preparedRecommendation() || deepRecommendation();
    }

    public boolean deepRecommendation() { return "shopping-recommendation-deep".equals(id); }

    public boolean deepWorkflow() { return "DEEP_RECOMMENDATION_WORKFLOW".equals(executionMode); }

    public record Catalog(int version, List<SkillDefinition> skills) {
    }
}
