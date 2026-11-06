package com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.execution;

import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillContext;

public final class AgentLoopExecutor implements SkillExecutor {
    public ModelStep nextStep(SkillContext context) { return ModelStep.agent(); }
}
