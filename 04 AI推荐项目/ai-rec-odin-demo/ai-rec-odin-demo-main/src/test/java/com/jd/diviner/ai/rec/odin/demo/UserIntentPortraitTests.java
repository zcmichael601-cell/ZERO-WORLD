package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.domain.recommendation.UserIntentPortrait;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserIntentPortraitTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void factsKeepProfileAndRecentBehavior() {
        ObjectNode profile = MAPPER.createObjectNode();
        profile.putArray("memories").add(MAPPER.createObjectNode().put("memory", "偏好低温奶和咖啡茶饮"));
        ObjectNode behaviors = MAPPER.createObjectNode();
        behaviors.putArray("memories").add(MAPPER.createObjectNode().put("memory", "浏览了鲜活大海白虾和西兰花"));
        String facts = UserIntentPortrait.facts(profile, behaviors);
        assertTrue(facts.contains("低温奶"));
        assertTrue(facts.contains("西兰花 ×1"));
        assertTrue(facts.indexOf("长期画像") < facts.indexOf("近期行为"));
    }

    @Test
    void recentBehaviorsCountIdenticalItems() {
        ObjectNode behaviors = MAPPER.createObjectNode();
        var memories = behaviors.putArray("memories");
        memories.add(memory("苹果手机", "click", "1001"));
        memories.add(memory("西兰花", "click", "2002"));
        memories.add(memory("苹果手机 256G", "click", "1001"));
        memories.add(memory("苹果手机", "order", "1001"));
        ObjectNode profile = MAPPER.createObjectNode();
        profile.putArray("memories");
        String facts = UserIntentPortrait.facts(profile, behaviors);
        assertTrue(facts.contains("[click] 苹果手机 ×2"));
        assertTrue(facts.contains("[click] 西兰花 ×1"));
        assertTrue(facts.contains("[order] 苹果手机 ×1"));
        assertEquals(1, facts.split("苹果手机 ×2", -1).length - 1);
        var counts = UserIntentPortrait.countedBehaviors(behaviors, MAPPER).path("counts");
        assertEquals(2, counts.get(0).path("count").asInt());
        assertEquals("1001", counts.get(0).path("skuId").asText());
    }

    private static ObjectNode memory(String text, String action, String sku) {
        ObjectNode memory = MAPPER.createObjectNode().put("memory", text);
        memory.putObject("metadata").put("memory_subtype2", action).put("skuid", sku);
        return memory;
    }

    @Test
    void stateUsesPlainModelText() {
        String state = UserIntentPortrait.state("""
                ```text
                用户偏好低温奶和咖啡，近期在看生鲜，包括大虾和西兰花。
                ```
                """, MAPPER);
        assertEquals("用户偏好低温奶和咖啡，近期在看生鲜，包括大虾和西兰花。", state);
    }

    @Test
    void categoryLevelsReadsTriple() {
        List<String> levels = UserIntentPortrait.categoryLevels("""
                用户近期在看生鲜，更可能买早餐肉肠。
                类目: 生鲜 > 肉制品 > 火腿肠
                """);
        assertEquals(List.of("生鲜", "肉制品", "火腿肠"), levels);
        assertEquals(List.of("生鲜"), UserIntentPortrait.categoryLevels("类目: 生鲜"));
        assertEquals(List.of("生鲜", "水饮冲调"), UserIntentPortrait.categoryChoices("类目: 生鲜、水饮冲调"));
        assertThrows(IllegalArgumentException.class, () -> UserIntentPortrait.categoryChoices("类目: 生鲜"));
        assertEquals(List.of("火腿肠"), UserIntentPortrait.categoryNameList("火腿肠", 1, 3, true));
        assertEquals(List.of("火腿肠", "腊肠", "培根"),
                UserIntentPortrait.categoryNameList("类目: 火腿肠、腊肠、培根", 1, 3, true));
        assertThrows(IllegalArgumentException.class,
                () -> UserIntentPortrait.categoryNameList("类目: a、b、c、d", 1, 3, true));
        assertEquals("用户近期在看生鲜，更可能买早餐肉肠。",
                UserIntentPortrait.withoutCategoryLine("""
                        用户近期在看生鲜，更可能买早餐肉肠。
                        类目: 生鲜 > 肉制品 > 火腿肠
                        """).replaceAll("\\s+", " "));
    }

    @Test
    void stateRejectsEmptyModelText() {
        assertThrows(IllegalArgumentException.class, () -> UserIntentPortrait.state("  \n", MAPPER));
    }
}
