package com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.execution;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillContext;
import java.util.List;

public interface SkillExecutor {
    default void start(SkillContext context) {}
    ModelStep nextStep(SkillContext context);
    default void toolsCompleted(List<String> tools) {}
    default boolean separatesToolAndAnswerPhases() { return false; }

    record ModelStep(Mode mode, String tool, boolean includeToolSchemas, boolean rebuildContext,
                     ObjectNode localOutput) {
        public enum Mode { AGENT, DECISION, ANSWER, COMPLETE }
        public static ModelStep agent() { return new ModelStep(Mode.AGENT, null, true, false, null); }
        public static ModelStep decision(String tool) { return new ModelStep(Mode.DECISION, tool, true, false, null); }
        public static ModelStep answer(boolean includeToolSchemas, boolean rebuildContext) {
            return new ModelStep(Mode.ANSWER, null, includeToolSchemas, rebuildContext, null);
        }
        public static ModelStep complete(ObjectNode output) {
            if (output == null) {
                throw new IllegalArgumentException("本地完成结果不能为空");
            }
            return new ModelStep(Mode.COMPLETE, null, false, false, output.deepCopy());
        }
    }
}
