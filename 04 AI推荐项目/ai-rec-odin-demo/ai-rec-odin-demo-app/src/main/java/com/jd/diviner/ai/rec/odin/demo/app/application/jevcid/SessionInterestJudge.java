package com.jd.diviner.ai.rec.odin.demo.app.application.jevcid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Side-path interest judge. Uses the same JoyAI chat model as skills, not JEV,
 * and does not write scores back into the recommendation agent.
 */
public final class SessionInterestJudge {

    public record Event(
            String skuId,
            String name,
            String brand,
            int cid2,
            String cid2Name,
            String cid1Name,
            boolean clicked,
            boolean pending,
            int screenNumber) {
    }

    public record Interest(
            int cid2,
            String cid2Name,
            String cid1Name,
            int interest,
            int confidence,
            String source,
            int clicks,
            int exposures) {
    }

    public record Verdict(List<Interest> interests, String summary) {
    }

    private static final String SYSTEM = """
            你是独立旁路兴趣判别模型，与推荐 Agent 无关，结果不会写回召回。
            必须同时使用三份证据，且本会话实时行为权重大于画像和历史：
            1. 用户画像（长期兴趣、人群、城市、预算、长期记忆）
            2. 历史行为（浏览/搜索/加购/购买等，不含本会话模拟操作）
            3. 本会话实时行为（点击=正反馈；曝光未点击=强负反馈）
            打分必须连续：以上一轮 previousInterests 为同一 cid2 的起点，只根据本轮新增点击/曝光微调，不要推翻重来。
            - 没有新证据的类目保持上一轮 interest
            - 同一 cid1 下新出现的相关二级类目，从已有兴趣附近起步，不要从 0 重开
            - 每次新增点击大约 +12
            - 每次新增曝光未点击大约 -18；已计入过的曝光不要再扣
            - 不要因为画像喜欢就维持高分，但也不要因为换了一屏相关商品就丢掉原兴趣
            只给 facts.candidates 中的二级类目打分，不要发明新类目。
            只输出一个 JSON 对象，不要 Markdown、解释或工具调用：
            {"interests":[{"cid2":653,"cid2Name":"手机","cid1Name":"手机通讯","interest":82,"confidence":70}],"summary":"一句话依据"}
            interest 和 confidence 必须是 0 到 100 的整数。
            """;

    private final ObjectMapper mapper;
    private final ChatModel chatModel;
    private final String modelName;

    public SessionInterestJudge(ObjectMapper mapper, SkillModelClient client) {
        this(mapper, client.chatModel(), client.modelName());
    }

    public SessionInterestJudge(ObjectMapper mapper, ChatModel chatModel, String modelName) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
    }

    public Verdict judge(
            JsonNode profile,
            JsonNode historyBehaviors,
            List<Interest> previous,
            List<Event> events) {
        List<Event> evidence = events == null ? List.of() : List.copyOf(events);
        List<Interest> priors = previous == null ? List.of() : List.copyOf(previous);
        if (evidence.isEmpty() && priors.isEmpty()) {
            return new Verdict(List.of(), "还没有点击或曝光，旁路模型尚未判别。");
        }
        Map<Integer, Category> categories = categories(priors, evidence);
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("旁路兴趣判别需要有效 cid2");
        }
        JsonNode scored = callModel(facts(profile, historyBehaviors, priors, evidence, categories));
        List<Interest> interests = new ArrayList<>();
        for (Category category : categories.values()) {
            JsonNode item = match(scored.path("interests"), category);
            int skips = Math.max(0, category.exposures - category.clicks);
            int confidence = item.path("confidence").isIntegralNumber()
                    ? clamp(item.path("confidence").asInt())
                    : Math.min(95, 20 + category.exposures * 6 + category.clicks * 10 + Math.min(20, skips * 3));
            int modelInterest = item.path("interest").isIntegralNumber()
                    ? clamp(item.path("interest").asInt())
                    : 0;
            interests.add(new Interest(
                    category.cid2,
                    category.cid2Name,
                    category.cid1Name,
                    continueInterest(priors, category, modelInterest),
                    confidence,
                    "BYPASS_LLM",
                    category.clicks,
                    category.exposures));
        }
        interests.sort(Comparator.comparingInt(Interest::interest).reversed()
                .thenComparing(Comparator.comparingInt(Interest::clicks).reversed())
                .thenComparingInt(Interest::cid2));
        int clicks = (int) evidence.stream().filter(Event::clicked).count();
        String summary = text(scored.path("summary").asText(""),
                "旁路大模型已根据画像、历史行为和 "
                        + clicks + " 次点击、"
                        + (evidence.size() - clicks) + " 次曝光未点击重判 "
                        + interests.size() + " 个二级类目；结果不写入推荐 Agent。");
        return new Verdict(List.copyOf(interests), clip(summary, 240));
    }

    public static List<Event> eventsFrom(JsonNode values) {
        if (values == null || !values.isArray()) {
            return List.of();
        }
        if (values.size() > 80) {
            throw new IllegalArgumentException("旁路兴趣事件不能超过 80 条");
        }
        List<Event> events = new ArrayList<>();
        for (JsonNode value : values) {
            int cid2 = value.path("cid2").asInt(0);
            if (cid2 <= 0) {
                continue;
            }
            events.add(new Event(
                    value.path("skuId").asText(""),
                    clip(value.path("name").asText(""), 80),
                    clip(value.path("brand").asText(""), 40),
                    cid2,
                    clip(value.path("cid2Name").asText(""), 40),
                    clip(value.path("cid1Name").asText(""), 40),
                    value.path("clicked").asBoolean(false),
                    value.path("pending").asBoolean(false),
                    value.path("screenNumber").asInt(0)));
        }
        return List.copyOf(events);
    }

    public static List<Interest> interestsFrom(JsonNode values) {
        if (values == null || !values.isArray()) {
            return List.of();
        }
        if (values.size() > 12) {
            throw new IllegalArgumentException("上一轮旁路兴趣不能超过 12 个");
        }
        List<Interest> interests = new ArrayList<>();
        for (JsonNode value : values) {
            int cid2 = value.path("cid2").asInt(0);
            if (cid2 <= 0) {
                continue;
            }
            interests.add(new Interest(
                    cid2,
                    clip(value.path("cid2Name").asText(""), 40),
                    clip(value.path("cid1Name").asText(""), 40),
                    clamp(value.path("interest").asInt(0)),
                    clamp(value.path("confidence").asInt(0)),
                    "BYPASS_LLM",
                    Math.max(0, value.path("clicks").asInt(0)),
                    Math.max(0, value.path("exposures").asInt(0))));
        }
        return List.copyOf(interests);
    }

    private JsonNode callModel(JsonNode facts) {
        var options = OpenAiChatOptions.builder()
                .model(modelName)
                .internalToolExecutionEnabled(false)
                .toolCallbacks(List.of())
                .streamUsage(false)
                .build();
        Prompt prompt = new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(facts.toString())), options);
        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("旁路大模型判别失败：" + exception.getMessage(), exception);
        }
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("旁路大模型返回了空响应");
        }
        return parseObject(response.getResult().getOutput().getText());
    }

    private ObjectNode facts(
            JsonNode profile,
            JsonNode historyBehaviors,
            List<Interest> previous,
            List<Event> events,
            Map<Integer, Category> categories) {
        ObjectNode facts = mapper.createObjectNode();
        facts.set("profile", compactProfile(profile));
        facts.set("historyBehaviors", compactBehaviors(profile, historyBehaviors));
        ArrayNode prior = facts.putArray("previousInterests");
        for (Interest interest : previous) {
            prior.addObject()
                    .put("cid2", interest.cid2())
                    .put("cid2Name", interest.cid2Name())
                    .put("cid1Name", interest.cid1Name())
                    .put("interest", interest.interest())
                    .put("confidence", interest.confidence())
                    .put("clicks", interest.clicks())
                    .put("exposures", interest.exposures());
        }
        ArrayNode live = facts.putArray("sessionEvents");
        int start = Math.max(0, events.size() - 40);
        for (int i = start; i < events.size(); i++) {
            Event event = events.get(i);
            live.addObject()
                    .put("screenNumber", Math.max(1, event.screenNumber()))
                    .put("action", event.clicked() ? (event.pending() ? "刚点击" : "已点击") : "曝光未点击")
                    .put("name", event.name())
                    .put("cid2", event.cid2())
                    .put("cid2Name", event.cid2Name())
                    .put("brand", event.brand());
        }
        ArrayNode candidates = facts.putArray("candidates");
        for (Category category : categories.values()) {
            int skips = Math.max(0, category.exposures - category.clicks);
            candidates.addObject()
                    .put("cid2", category.cid2)
                    .put("cid2Name", category.cid2Name)
                    .put("cid1Name", category.cid1Name)
                    .put("clicks", category.clicks)
                    .put("exposures", category.exposures)
                    .put("skips", skips)
                    .put("skipWeight", 18);
        }
        return facts;
    }

    private ObjectNode compactProfile(JsonNode profile) {
        ObjectNode out = mapper.createObjectNode();
        if (profile == null || !profile.isObject()) {
            return out;
        }
        copyText(out, profile, "username", "segment", "city");
        if (profile.path("budget").isNumber()) {
            out.put("budget", profile.path("budget").asDouble());
        }
        ArrayNode interests = out.putArray("interests");
        for (JsonNode item : profile.path("interests")) {
            if (interests.size() >= 12) {
                break;
            }
            String text = item.isTextual() ? item.asText() : item.path("memory").asText("");
            if (!text.isBlank()) {
                interests.add(clip(text, 80));
            }
        }
        ArrayNode memories = out.putArray("memories");
        for (JsonNode item : profile.path("memories")) {
            if (memories.size() >= 8) {
                break;
            }
            String text = item.isTextual() ? item.asText() : item.path("memory").asText("");
            if (!text.isBlank()) {
                memories.add(clip(text, 200));
            }
        }
        return out;
    }

    private ArrayNode compactBehaviors(JsonNode profile, JsonNode historyBehaviors) {
        ArrayNode out = mapper.createArrayNode();
        JsonNode source = historyBehaviors != null && (historyBehaviors.isArray() || historyBehaviors.isObject())
                ? historyBehaviors
                : profile == null ? mapper.missingNode() : profile.path("behaviors");
        if (source.isObject()) {
            source = source.path("signals").isArray() ? source.path("signals") : source.path("counts");
        }
        if (!source.isArray()) {
            return out;
        }
        for (JsonNode item : source) {
            if (out.size() >= 20) {
                break;
            }
            String text = item.isTextual() ? item.asText() : behaviorLine(item);
            if (!text.isBlank()) {
                out.add(clip(text, 120));
            }
        }
        return out;
    }

    private JsonNode parseObject(String text) {
        String content = text == null ? "" : text.strip();
        if (content.startsWith("```")) {
            int start = content.indexOf('\n');
            int end = content.lastIndexOf("```");
            if (start > 0 && end > start) {
                content = content.substring(start + 1, end).strip();
            }
        }
        int from = content.indexOf('{');
        int to = content.lastIndexOf('}');
        if (from < 0 || to <= from) {
            throw new IllegalStateException("旁路大模型未返回 JSON");
        }
        try {
            JsonNode parsed = mapper.readTree(content.substring(from, to + 1));
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalStateException("旁路大模型未返回对象");
            }
            return parsed;
        } catch (IOException exception) {
            throw new IllegalStateException("旁路大模型返回了无法解析的 JSON", exception);
        }
    }

    private static Map<Integer, Category> categories(List<Interest> priors, List<Event> evidence) {
        Map<Integer, Category> categories = new LinkedHashMap<>();
        for (Interest prior : priors) {
            if (prior.cid2() <= 0) {
                continue;
            }
            categories.put(prior.cid2(), new Category(
                    prior.cid2(),
                    text(prior.cid2Name(), "cid2 " + prior.cid2()),
                    text(prior.cid1Name(), ""),
                    0,
                    0));
        }
        for (Event event : evidence) {
            if (event.cid2() <= 0) {
                continue;
            }
            Category current = categories.getOrDefault(event.cid2(), new Category(
                    event.cid2(),
                    text(event.cid2Name(), "cid2 " + event.cid2()),
                    text(event.cid1Name(), ""),
                    0,
                    0));
            categories.put(event.cid2(), new Category(
                    current.cid2,
                    text(event.cid2Name(), current.cid2Name),
                    text(event.cid1Name(), current.cid1Name),
                    current.exposures + 1,
                    current.clicks + (event.clicked() ? 1 : 0)));
        }
        if (categories.size() <= 12) {
            return categories;
        }
        List<Category> ranked = new ArrayList<>(categories.values());
        ranked.sort(Comparator
                .comparingInt((Category item) -> item.clicks).reversed()
                .thenComparing(Comparator.comparingInt((Category item) -> item.exposures).reversed()));
        Map<Integer, Category> limited = new LinkedHashMap<>();
        for (Category category : ranked.subList(0, 12)) {
            limited.put(category.cid2, category);
        }
        return limited;
    }

    private static JsonNode match(JsonNode interests, Category category) {
        if (interests != null && interests.isArray()) {
            for (JsonNode item : interests) {
                if (item.path("cid2").asInt(0) == category.cid2) {
                    return item;
                }
            }
            for (JsonNode item : interests) {
                if (category.cid2Name.equals(item.path("cid2Name").asText())) {
                    return item;
                }
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static void copyText(ObjectNode target, JsonNode source, String... fields) {
        for (String field : fields) {
            String value = source.path(field).asText("");
            if (!value.isBlank()) {
                target.put(field, clip(value, 40));
            }
        }
    }

    private static String behaviorLine(JsonNode item) {
        String action = item.path("action").asText("");
        String text = item.path("text").asText(item.path("memory").asText(""));
        String skuId = item.path("skuId").asText("");
        int count = item.path("count").asInt(0);
        StringBuilder line = new StringBuilder();
        if (!action.isBlank()) {
            line.append('[').append(action).append("] ");
        }
        line.append(text.isBlank() ? (skuId.isBlank() ? "" : "SKU " + skuId) : text);
        if (!skuId.isBlank() && !line.toString().contains(skuId)) {
            line.append(" · SKU ").append(skuId);
        }
        if (count > 1) {
            line.append(" ×").append(count);
        }
        return line.toString().strip();
    }

    private static int continueInterest(List<Interest> priors, Category category, int modelInterest) {
        Interest prior = findPrior(priors, category.cid2);
        int skips = Math.max(0, category.exposures - category.clicks);
        if (prior != null) {
            if (category.exposures < prior.exposures() && category.clicks <= prior.clicks()) {
                return prior.interest();
            }
            int clickDelta = category.clicks - prior.clicks();
            int skipDelta = skips - Math.max(0, prior.exposures() - prior.clicks());
            return clamp(prior.interest() + clickDelta * 12 - skipDelta * 18);
        }
        Interest sibling = findSibling(priors, category);
        if (sibling != null) {
            int inherited = Math.max(20, sibling.interest() - 8);
            return clamp(inherited + category.clicks * 12 - Math.min(skips, 2) * 8);
        }
        return applyLivePressure(modelInterest, category.clicks, category.exposures);
    }

    private static Interest findPrior(List<Interest> priors, int cid2) {
        for (Interest prior : priors) {
            if (prior.cid2() == cid2) {
                return prior;
            }
        }
        return null;
    }

    private static Interest findSibling(List<Interest> priors, Category category) {
        if (category.cid1Name == null || category.cid1Name.isBlank()) {
            return null;
        }
        Interest best = null;
        for (Interest prior : priors) {
            if (!category.cid1Name.equals(prior.cid1Name())) {
                continue;
            }
            if (best == null || prior.interest() > best.interest()) {
                best = prior;
            }
        }
        return best;
    }

    private static int applyLivePressure(int modelInterest, int clicks, int exposures) {
        int skips = Math.max(0, exposures - clicks);
        if (skips == 0) {
            return clamp(modelInterest);
        }
        if (clicks == 0) {
            return clamp(Math.min(modelInterest - skips * 18, 45 - skips * 18));
        }
        return clamp(modelInterest - Math.max(0, skips - clicks) * 12);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value.strip();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private record Category(int cid2, String cid2Name, String cid1Name, int exposures, int clicks) {
    }
}
