package com.jd.diviner.ai.rec.odin.demo.app.application.jevcid;

import com.fasterxml.jackson.databind.JsonNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillBehavior;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillContext;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillExtension;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillDefinition;
import com.jd.diviner.ai.rec.odin.demo.app.domain.user.UserIdentifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Isolated extension for the cid catalog -> Sniffer recommendation workflow. */
public final class JevCidSkillExtension implements SkillExtension {

    static final List<String> TOOLS = List.of(
            "get_user_profile",
            "get_user_behaviors",
            "select_cid1",
            "recall_cid2");
    static final List<String> REQUIRED_TOOLS = List.of(
            "get_user_profile",
            "get_user_behaviors",
            "recall_cid2");

    @Override
    public String name() {
        return "jev-cid";
    }

    @Override
    public SkillBehavior create(SkillContext context) {
        return new JevCidSkillBehavior(context);
    }

    @Override
    public boolean supportsLegacy(SkillDefinition skill) {
        return "shopping-recommendation-jev-cid".equals(skill.id());
    }

    @Override
    public boolean supportsMode(String mode) {
        return "WORKFLOW".equals(mode);
    }

    @Override
    public int minimumModelCalls(SkillDefinition skill) {
        return 1;
    }

    @Override
    public boolean completesLocally(SkillDefinition skill) {
        return true;
    }

    @Override
    public void validateDefinition(SkillDefinition skill) {
        if (!"shopping-recommendation-jev-cid".equals(skill.id())
                || !skill.requiredInputs().equals(List.of("username"))
                || !skill.tools().equals(TOOLS)
                || !skill.requiredTools().equals(REQUIRED_TOOLS)
                || !skill.requiredOutputs().equals(List.of("products"))
                || skill.maxModelCalls() < 1
                || skill.maxToolCalls() < 5) {
            throw new IllegalArgumentException(
                    "类目意图 Skill 需配置 username、画像行为与两步类目工具、products 输出、至少1轮首屏类目决策和5次工具调用");
        }
    }

    @Override
    public void validateInput(SkillDefinition skill, JsonNode input) {
        if (input.has("userDataMode") && (!input.path("userDataMode").isTextual()
                || !"REAL".equals(input.path("userDataMode").asText()))) {
            throw new IllegalArgumentException("类目意图 Skill 仅支持 userDataMode=REAL");
        }
        if (input.has("username") && (!input.path("username").isTextual()
                || !UserIdentifier.validate(input.path("username").asText())
                .equals(input.path("username").asText()))) {
            throw new IllegalArgumentException("username 必须为有效且无首尾空白的用户标识");
        }
        validateInteraction(input);
    }

    private static void validateInteraction(JsonNode input) {
        JsonNode clicks = input.get("simulatedClickSkuIds");
        JsonNode exposures = input.get("exposedSkuIds");
        JsonNode screen = input.get("screenNumber");
        JsonNode interests = input.get("initialInterests");
        JsonNode currentInterests = input.get("currentInterests");
        JsonNode relatedCid2 = input.get("relatedCid2");
        JsonNode events = input.get("sessionEvents");
        JsonNode usedQueries = input.get("usedQueries");
        if (clicks == null && exposures == null && screen == null
                && interests == null && currentInterests == null
                && relatedCid2 == null && events == null && usedQueries == null) {
            return;
        }
        if (screen == null || !screen.isIntegralNumber() || !screen.canConvertToInt()
                || screen.asInt() < 1 || screen.asInt() > 100) {
            throw new IllegalArgumentException("screenNumber 必须为 1～100 的整数");
        }
        if (screen.asInt() == 1) {
            if (clicks != null || exposures != null || interests != null
                    || currentInterests != null || relatedCid2 != null || events != null
                    || usedQueries != null) {
                throw new IllegalArgumentException("首屏不能预置模拟点击、曝光或会话兴趣数据");
            }
            return;
        }

        Set<String> exposed = validateSkuArray(exposures, 1, 100, "曝光 SKU");
        Set<String> clicked = clicks == null
                ? Set.of()
                : validateSkuArray(clicks, 0, 10, "模拟点击 SKU");
        if (!exposed.containsAll(clicked)) {
            throw new IllegalArgumentException("模拟点击 SKU 必须来自本屏曝光 SKU");
        }
        validateInterests(interests, "initialInterests");
        validateInterests(currentInterests, "currentInterests");
        validateRelatedCid2(relatedCid2);
        validateUsedQueries(usedQueries);
        validateSessionEvents(events, screen.asInt(), exposed, clicked);
    }

    private static void validateInterests(JsonNode interests, String field) {
        if (interests == null) {
            return;
        }
        if (!interests.isArray() || interests.isEmpty() || interests.size() > 5) {
            throw new IllegalArgumentException(field + " 需包含 1～5 个兴趣");
        }
        Set<Integer> cid2s = new HashSet<>();
        Set<String> allowed = Set.of("cid2", "cid2Name", "interest", "confidence", "cid1Name", "brand", "query");
        for (JsonNode interest : interests) {
            if (!interest.isObject()
                    || !positiveInteger(interest.path("cid2"))
                    || !validText(interest.path("cid2Name"), 100, false)
                    || !score(interest.path("interest"))
                    || !score(interest.path("confidence"))
                    || (interest.has("cid1Name") && !validText(interest.path("cid1Name"), 100, false))
                    || (interest.has("brand") && !validText(interest.path("brand"), 100, false))
                    || (interest.has("query") && !validText(interest.path("query"), 120, false))
                    || !cid2s.add(interest.path("cid2").asInt())) {
                throw new IllegalArgumentException(field + " 格式无效");
            }
            var fields = interest.fieldNames();
            while (fields.hasNext()) {
                if (!allowed.contains(fields.next())) {
                    throw new IllegalArgumentException(field + " 格式无效");
                }
            }
        }
    }

    private static void validateRelatedCid2(JsonNode related) {
        if (related == null) {
            return;
        }
        if (!related.isArray() || related.isEmpty() || related.size() > 50) {
            throw new IllegalArgumentException("relatedCid2 需包含 1～50 个相关二级类目");
        }
        Set<Integer> cid2s = new HashSet<>();
        Set<String> allowed = Set.of("cid2", "cid2Name", "cid1Name");
        for (JsonNode value : related) {
            if (!value.isObject()
                    || !positiveInteger(value.path("cid2"))
                    || !validText(value.path("cid2Name"), 100, false)
                    || (value.has("cid1Name") && !validText(value.path("cid1Name"), 100, false))
                    || !cid2s.add(value.path("cid2").asInt())) {
                throw new IllegalArgumentException("relatedCid2 格式无效");
            }
            var fields = value.fieldNames();
            while (fields.hasNext()) {
                if (!allowed.contains(fields.next())) {
                    throw new IllegalArgumentException("relatedCid2 格式无效");
                }
            }
        }
    }

    private static void validateSessionEvents(
            JsonNode events,
            int screenNumber,
            Set<String> exposed,
            Set<String> clicked) {
        if (events == null) {
            return;
        }
        if (!events.isArray() || events.isEmpty() || events.size() > 100) {
            throw new IllegalArgumentException("sessionEvents 需包含 1～100 条会话行为");
        }
        Set<String> unique = new HashSet<>();
        Set<String> currentExposures = new HashSet<>();
        Set<String> currentClicks = new HashSet<>();
        Set<String> allowed = Set.of(
                "screenNumber", "skuId", "name", "brand", "cid2", "cid3",
                "cid2Name", "cid3Name", "category", "clicked");
        for (JsonNode event : events) {
            if (!event.isObject() || !event.path("screenNumber").isIntegralNumber()
                    || !event.path("screenNumber").canConvertToInt()
                    || event.path("screenNumber").asInt() < 1
                    || event.path("screenNumber").asInt() >= screenNumber
                    || !event.path("skuId").isTextual()
                    || !event.path("skuId").asText().matches("[1-9][0-9]{5,19}")
                    || !validText(event.path("name"), 500, false)
                    || !event.path("clicked").isBoolean()) {
                throw new IllegalArgumentException("sessionEvents 行为字段无效");
            }
            var fields = event.fieldNames();
            while (fields.hasNext()) {
                if (!allowed.contains(fields.next())) {
                    throw new IllegalArgumentException("sessionEvents 包含未知字段");
                }
            }
            for (String field : List.of("brand", "cid2Name", "cid3Name", "category")) {
                if (event.has(field) && !validText(event.path(field), field.equals("category") ? 200 : 100, true)) {
                    throw new IllegalArgumentException("sessionEvents." + field + " 无效");
                }
            }
            for (String field : List.of("cid2", "cid3")) {
                if (event.has(field) && !positiveInteger(event.path(field))) {
                    throw new IllegalArgumentException("sessionEvents." + field + " 必须为正整数");
                }
            }
            String key = event.path("screenNumber").asInt() + ":" + event.path("skuId").asText();
            if (!unique.add(key)) {
                throw new IllegalArgumentException("sessionEvents 同一屏不能重复 SKU");
            }
            if (event.path("screenNumber").asInt() == screenNumber - 1) {
                currentExposures.add(event.path("skuId").asText());
                if (event.path("clicked").asBoolean()) {
                    currentClicks.add(event.path("skuId").asText());
                }
            }
        }
        if (!currentExposures.equals(exposed) || !currentClicks.equals(clicked)) {
            throw new IllegalArgumentException("sessionEvents 必须完整对应上一屏曝光与点击");
        }
    }

    private static void validateUsedQueries(JsonNode queries) {
        if (queries == null) {
            return;
        }
        if (!queries.isArray() || queries.size() > 20) {
            throw new IllegalArgumentException("usedQueries 需为不超过 20 条查询");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode query : queries) {
            if (!validText(query, 120, false) || !unique.add(query.asText().strip())) {
                throw new IllegalArgumentException("usedQueries 格式无效");
            }
        }
    }

    private static boolean validText(JsonNode value, int maximum, boolean allowEmpty) {
        if (!value.isTextual()) {
            return false;
        }
        String text = value.asText();
        return (allowEmpty || !text.isBlank())
                && text.length() <= maximum
                && text.chars().noneMatch(Character::isISOControl);
    }

    private static boolean positiveInteger(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0;
    }

    private static boolean score(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt()
                && value.asInt() >= 0 && value.asInt() <= 100;
    }

    private static Set<String> validateSkuArray(
            JsonNode values,
            int minimum,
            int maximum,
            String label) {
        if (values == null || !values.isArray()
                || values.size() < minimum || values.size() > maximum) {
            throw new IllegalArgumentException(
                    label + "需包含 " + minimum + "～" + maximum + " 个 SKU");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || !value.asText().matches("[1-9][0-9]{5,19}")
                    || !unique.add(value.asText())) {
                throw new IllegalArgumentException(label + "必须是互不重复的有效 SKU");
            }
        }
        return Set.copyOf(unique);
    }
}
