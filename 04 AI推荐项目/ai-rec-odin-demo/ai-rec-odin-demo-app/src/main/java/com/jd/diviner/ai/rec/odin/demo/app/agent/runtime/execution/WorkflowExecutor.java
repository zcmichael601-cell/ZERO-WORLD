package com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillContext;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public final class WorkflowExecutor implements SkillExecutor {
    private final List<Step> steps;
    private int cursor;

    public WorkflowExecutor(List<Step> steps) {
        this.steps = List.copyOf(steps);
        if (steps.isEmpty()
                || !(steps.get(steps.size() - 1) instanceof Answer
                        || steps.get(steps.size() - 1) instanceof Complete)
                || steps.stream().filter(step -> step instanceof Answer || step instanceof Complete).count() != 1) {
            throw new IllegalArgumentException("Workflow 必须以唯一的 Answer 或 Complete 节点结束");
        }
    }

    public void start(SkillContext context) { nextStep(context); }
    public boolean separatesToolAndAnswerPhases() { return true; }

    public ModelStep nextStep(SkillContext context) {
        while (true) {
            context.checkActive();
            Step step = steps.get(cursor);
            if (step instanceof Tool tool) {
                context.invokeTool(tool.name(), tool.arguments().apply(context));
            } else if (step instanceof Local local) {
                local.action().accept(context);
            } else if (step instanceof Decision decision) {
                return ModelStep.decision(decision.tool());
            } else if (step instanceof Answer answer) {
                return ModelStep.answer(answer.includeToolSchemas(), answer.rebuildContext());
            } else if (step instanceof Complete complete) {
                return ModelStep.complete(complete.output().apply(context));
            }
            cursor++;
        }
    }

    public void toolsCompleted(List<String> tools) {
        if (steps.get(cursor) instanceof Decision decision && tools.equals(List.of(decision.tool()))) cursor++;
    }

    public sealed interface Step permits Tool, Local, Decision, Answer, Complete {}
    public record Tool(String name, Function<SkillContext, JsonNode> arguments) implements Step {
        public Tool { Objects.requireNonNull(name); Objects.requireNonNull(arguments); }
    }
    public record Local(Consumer<SkillContext> action) implements Step {
        public Local { Objects.requireNonNull(action); }
    }
    public record Decision(String tool) implements Step {
        public Decision { Objects.requireNonNull(tool); }
    }
    public record Answer(boolean includeToolSchemas, boolean rebuildContext) implements Step {}
    public record Complete(Function<SkillContext, ObjectNode> output) implements Step {
        public Complete { Objects.requireNonNull(output); }
    }
}
