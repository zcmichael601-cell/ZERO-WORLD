package com.jd.diviner.ai.rec.odin.demo.app.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillDefinition;
import java.util.List;

public interface SkillExtension {
    String name();
    SkillBehavior create(SkillContext context);
    default boolean supportsLegacy(SkillDefinition skill) { return false; }
    default boolean supportsMode(String mode) { return mode == null || List.of("AGENT", "WORKFLOW").contains(mode); }
    default int minimumModelCalls(SkillDefinition skill) { return "WORKFLOW".equals(skill.executionMode()) ? 1 : 2; }
    default boolean completesLocally(SkillDefinition skill) { return false; }
    default void validateDefinition(SkillDefinition skill) {}
    default void validateInput(SkillDefinition skill, JsonNode input) {}
    default void validateUserDataMode(SkillDefinition skill, String mode) {
        if (!"REAL".equals(mode)) throw new IllegalArgumentException("该 Skill 不支持此用户来源");
    }
}
