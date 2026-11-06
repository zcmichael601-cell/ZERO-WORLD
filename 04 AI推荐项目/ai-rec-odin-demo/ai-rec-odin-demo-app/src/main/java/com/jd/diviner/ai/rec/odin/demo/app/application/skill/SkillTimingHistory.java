package com.jd.diviner.ai.rec.odin.demo.app.application.skill;

import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillDefinition;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public class SkillTimingHistory {
    private final Map<String, List<Sample>> history = new LinkedHashMap<>(16, 0.75f, true);
    private final BooleanSupplier knowledgeReady;

    public SkillTimingHistory(BooleanSupplier knowledgeReady) { this.knowledgeReady = knowledgeReady; }

    public synchronized Plan plan(SkillDefinition skill, String model, String instructions, boolean streaming) {
        return plan(skill, model, instructions, streaming, "REAL");
    }

    public synchronized Plan plan(SkillDefinition skill, String model, String instructions, boolean streaming, String userDataMode) {
        return plan(skill, model, instructions, streaming, userDataMode, false);
    }

    public synchronized Plan plan(
            SkillDefinition skill,
            String model,
            String instructions,
            boolean streaming,
            String userDataMode,
            boolean localCompletion) {
        boolean cold = skill.tools().contains("retrieve_product_knowledge") && !knowledgeReady.getAsBoolean();
        String key = UUID.nameUUIDFromBytes((skill + "\n" + model + "\n" + instructions + "\n"
                + streaming + "\n" + cold + "\n" + userDataMode + "\n" + localCompletion)
                .getBytes(StandardCharsets.UTF_8)).toString();
        List<Sample> samples = history.getOrDefault(key, List.of());
        List<StageEstimate> seeds = seeds(skill, cold, localCompletion);
        double weight = Math.min(1, samples.size() / 5.0);
        long prior = seeds.stream().mapToLong(StageEstimate::expectedMs).sum();
        long expected = blend(prior, quantile(samples.stream().map(Sample::elapsedMs).toList(), 0.5), weight);
        List<StageEstimate> stages = new ArrayList<>();
        for (StageEstimate seed : seeds) {
            List<Long> durations = samples.stream().map(sample -> sample.durations().getOrDefault(seed.id(), 0L)).toList();
            long duration = blend(seed.expectedMs(), quantile(durations, 0.5), weight);
            long upper = Math.max(duration, blend(seed.upperMs(), quantile(durations, 0.9), weight));
            double calls = samples.isEmpty() ? seed.expectedCalls() : seed.expectedCalls() * (1 - weight)
                    + samples.stream().mapToInt(sample -> sample.calls().getOrDefault(seed.id(), 0)).average().orElse(1) * weight;
            stages.add(new StageEstimate(seed.id(), seed.label(), duration, upper, Math.max(1, calls)));
        }
        double scale = (double) expected / stages.stream().mapToLong(StageEstimate::expectedMs).sum();
        stages = stages.stream().map(stage -> new StageEstimate(stage.id(), stage.label(), Math.max(1, Math.round(stage.expectedMs() * scale)),
                Math.max(1, Math.round(stage.upperMs() * scale)), stage.expectedCalls())).toList();
        expected = stages.stream().mapToLong(StageEstimate::expectedMs).sum();
        long lower = Math.min(Math.round(expected * 0.75), blend(Math.round(prior * 0.6),
                quantile(samples.stream().map(Sample::elapsedMs).toList(), 0.2), weight));
        long upper = Math.max(Math.round(expected * 1.25), blend(Math.round(prior * 1.6),
                quantile(samples.stream().map(Sample::elapsedMs).toList(), 0.9), weight));
        return new Plan(key, new Estimate(skill.id(), model, cold, samples.isEmpty() ? "INITIAL" : samples.size() < 5 ? "BLENDED" : "HISTORY",
                samples.size(), expected, lower, upper, stages));
    }

    public synchronized void record(String runId, Plan plan, Report report, boolean success) {
        if (!success || report.elapsedMs() <= 0) return;
        List<Sample> samples = history.computeIfAbsent(plan.key(), ignored -> new ArrayList<>());
        if (samples.stream().anyMatch(sample -> sample.runId().equals(runId))) return;
        Map<String, Long> durations = new LinkedHashMap<>();
        Map<String, Integer> calls = new LinkedHashMap<>();
        report.stages().forEach(stage -> { durations.put(stage.id(), stage.durationMs()); calls.put(stage.id(), stage.executions()); });
        samples.add(new Sample(runId, report.elapsedMs(), Map.copyOf(durations), Map.copyOf(calls)));
        if (samples.size() > 30) samples.remove(0);
        while (history.size() > 64) history.remove(history.keySet().iterator().next());
    }

    private static long blend(long prior, long observed, double weight) {
        return Math.max(1, Math.round(prior * (1 - weight) + observed * weight));
    }

    private static long quantile(List<Long> values, double fraction) {
        if (values.isEmpty()) return 0;
        var sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil((sorted.size() - 1) * fraction));
    }

    public static String toolStage(String tool) {
        return switch (tool) {
            case "Skill" -> "loading";
            case "get_user_profile", "get_user_behaviors" -> "user";
            case "search_products" -> "products";
            case "retrieve_product_knowledge" -> "knowledge";
            case "get_beijing_weather" -> "weather";
            case "get_beijing_hot_attractions" -> "attractions";
            default -> "tools";
        };
    }

    private static List<StageEstimate> seeds(SkillDefinition skill, boolean cold, boolean localCompletion) {
        Map<String, StageEstimate> stages = new LinkedHashMap<>();
        if (skill.preparedRecommendation()) {
            add(stages, "loading", "本地加载技能", 10, 1);
            add(stages, "user", "用户信息与行为", 800, 2);
            add(stages, "products", "检索候选商品（无知识对比）", 100, 1);
            add(stages, "synthesis", "模型一次生成简短推荐", 4000, 1);
            add(stages, "validation", "校验结果", 40, 1);
            add(stages, "overhead", "流程衔接与其他处理", 50, 1);
            return List.copyOf(stages.values());
        }
        add(stages, "loading", skill.deepWorkflow() ? "本地加载技能" : "加载技能", skill.deepWorkflow() ? 10 : 3500, skill.deepWorkflow() ? 1 : 2);
        if ("shopping-recommendation-jev-cid".equals(skill.id())) {
            add(stages, "planning", "模型决策类目", 8000, 1);
        } else if (!localCompletion) {
            int rounds = skill.deepWorkflow() ? 2 : Math.max(1, (int) Math.ceil(skill.requiredTools().size() / 1.5));
            add(stages, "planning", "模型决策与工具选择", rounds * 8000L, rounds);
        }
        for (String tool : skill.tools()) {
            String stage = toolStage(tool);
            if (stages.containsKey(stage)) continue;
            switch (stage) {
                case "user" -> add(stages, stage, "用户信息与行为", 800, 2);
                case "products" -> add(stages, stage, skill.deepRecommendation() ? "多兴趣融合召回候选" : "检索候选商品", skill.deepRecommendation() ? 15000 : 800, 1);
                case "knowledge" -> add(stages, stage, cold ? "商品知识检索（含首次索引）" : "商品知识检索", cold ? 30000 : 2000, 1);
                case "weather" -> add(stages, stage, "查询天气", 6000, 1);
                case "attractions" -> add(stages, stage, "查询旅游景点", 12000, 1);
                default -> add(stages, stage, "其他工具", 2000, 1);
            }
        }
        if (!localCompletion) {
            long synthesis = skill.deepWorkflow() ? 60000 : skill.deepRecommendation() ? 240000
                    : skill.id().equals("shopping-recommendation") ? 110000
                    : skill.id().equals("user-insight") ? 60000 : 90000;
            add(stages, "synthesis", "模型分析与结果生成", synthesis, 1);
        }
        add(stages, "validation", "校验结果", 500, 1);
        add(stages, "overhead", "流程衔接与其他处理", 500, 1);
        return List.copyOf(stages.values());
    }

    private static void add(Map<String, StageEstimate> stages, String id, String label, long duration, int calls) {
        stages.put(id, new StageEstimate(id, label, duration, Math.round(duration * 1.6), calls));
    }

    public static final class Tracker {
        private final Plan plan;
        private final LongSupplier clock;
        private final long start;
        private final Map<String, Long> durations = new LinkedHashMap<>();
        private final Map<String, Integer> executions = new LinkedHashMap<>();
        private final java.util.Set<String> completed = new java.util.HashSet<>();
        private String active;
        private long entered;

        public Tracker(Plan plan, long start) { this(plan, start, System::nanoTime); }
        public Tracker(Plan plan, long start, LongSupplier clock) {
            this.plan = plan;
            this.start = start;
            this.clock = clock;
        }

        public synchronized void enter(String stage) {
            leave();
            active = stage;
            entered = clock.getAsLong();
            executions.merge(stage, 1, Integer::sum);
            completed.remove(stage);
        }

        public synchronized void leave() {
            if (active != null) durations.merge(active, Math.max(0, clock.getAsLong() - entered), Long::sum);
            active = null;
        }

        public synchronized void completed(String stage) { completed.add(stage); }

        public synchronized Snapshot snapshot(boolean includeEstimate) {
            long now = clock.getAsLong();
            long total = Math.max(0, (now - start) / 1_000_000);
            List<StageMeasurement> stages = new ArrayList<>();
            long accounted = 0;
            for (StageEstimate stage : plan.estimate().stages()) {
                if (stage.id().equals("overhead")) continue;
                boolean running = stage.id().equals(active);
                long current = running ? Math.max(0, now - entered) : 0;
                long duration = (durations.getOrDefault(stage.id(), 0L) + current) / 1_000_000;
                accounted += duration;
                stages.add(new StageMeasurement(stage.id(), stage.label(), duration, executions.getOrDefault(stage.id(), 0),
                        running, current / 1_000_000, completed.contains(stage.id()) && !running));
            }
            stages.add(new StageMeasurement("overhead", "流程衔接与其他处理", Math.max(0, total - accounted), 1,
                    active == null, 0, false));
            return new Snapshot(total, active == null ? "overhead" : active, List.copyOf(stages), includeEstimate ? plan.estimate() : null);
        }

        public synchronized Report report(boolean success) {
            leave();
            Snapshot snapshot = snapshot(false);
            var stages = snapshot.stages().stream().map(stage -> new StageMeasurement(stage.id(), stage.label(), stage.durationMs(),
                    stage.executions(), false, 0, stage.complete() || success && stage.executions() > 0)).toList();
            return new Report(snapshot.elapsedMs(), plan.estimate(), stages, snapshot.elapsedMs() - plan.estimate().expectedMs());
        }
    }

    private record Sample(String runId, long elapsedMs, Map<String, Long> durations, Map<String, Integer> calls) {}
    public record Plan(String key, Estimate estimate) {}
    public record StageEstimate(String id, String label, long expectedMs, long upperMs, double expectedCalls) {}
    public record Estimate(String skillId, String model, boolean coldStart, String basis, int sampleCount,
                           long expectedMs, long lowerMs, long upperMs, List<StageEstimate> stages) {}
    public record StageMeasurement(String id, String label, long durationMs, int executions, boolean active, long activeElapsedMs, boolean complete) {}
    public record Snapshot(long elapsedMs, String activeStage, List<StageMeasurement> stages, Estimate estimate) {}
    public record Report(long elapsedMs, Estimate estimate, List<StageMeasurement> stages, long predictionErrorMs) {}
}
