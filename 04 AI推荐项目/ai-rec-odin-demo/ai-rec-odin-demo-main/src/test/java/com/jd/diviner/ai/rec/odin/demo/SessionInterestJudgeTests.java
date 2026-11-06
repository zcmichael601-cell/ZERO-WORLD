package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.application.jevcid.SessionInterestJudge;
import com.jd.diviner.ai.rec.odin.demo.app.application.jevcid.SessionInterestJudge.Event;
import com.jd.diviner.ai.rec.odin.demo.app.application.jevcid.SessionInterestJudge.Interest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionInterestJudgeTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void scoresWithProfileHistoryAndLiveSession() {
        AtomicReference<String> prompt = new AtomicReference<>();
        var judge = new SessionInterestJudge(MAPPER, capturingModel(prompt, """
                {"interests":[
                  {"cid2":653,"cid2Name":"手机","cid1Name":"手机通讯","interest":92,"confidence":80},
                  {"cid2":830,"cid2Name":"手机配件","interest":11,"confidence":40}
                ],"summary":"画像偏好数码，历史在看手机，本会话点击手机、曝光未点击配件"}
                """), "test-model");

        ObjectNode profile = MAPPER.createObjectNode()
                .put("username", "u1")
                .put("segment", "数码爱好者")
                .put("city", "北京");
        profile.putArray("interests").add("手机").add("数码");
        profile.putArray("memories").add("长期关注苹果手机");
        var verdict = judge.judge(
                profile,
                MAPPER.createArrayNode().add("浏览 iPhone 详情页").add("搜索 华为 Mate"),
                List.of(),
                List.of(
                        new Event("1001", "iPhone", "Apple/苹果", 653, "手机", "手机通讯", true, true, 1),
                        new Event("1002", "湿巾", "京东京造", 830, "手机配件", "手机通讯", false, true, 1)));

        assertEquals(2, verdict.interests().size());
        assertEquals(653, verdict.interests().get(0).cid2());
        assertEquals(92, verdict.interests().get(0).interest());
        assertEquals(0, verdict.interests().get(1).interest());
        assertEquals("BYPASS_LLM", verdict.interests().get(0).source());
        String text = prompt.get();
        assertTrue(text.contains("数码爱好者"));
        assertTrue(text.contains("长期关注苹果手机"));
        assertTrue(text.contains("浏览 iPhone 详情页"));
        assertTrue(text.contains("刚点击"));
        assertTrue(text.contains("曝光未点击"));
        assertTrue(text.contains("\"cid2\":653"));
    }

    @Test
    void readsEventsFromJsonAndKeepsPreviousScoresAsContext() {
        AtomicReference<String> prompt = new AtomicReference<>();
        var judge = new SessionInterestJudge(MAPPER, capturingModel(prompt, """
                {"interests":[
                  {"cid2":653,"cid2Name":"手机","interest":80,"confidence":70},
                  {"cid2":12345,"cid2Name":"智能设备","interest":40,"confidence":50}
                ],"summary":"点击手机后下调智能设备"}
                """), "test-model");
        ObjectNode body = MAPPER.createObjectNode();
        body.putArray("previous").addObject()
                .put("cid2", 12345)
                .put("cid2Name", "智能设备")
                .put("interest", 85)
                .put("confidence", 80);
        body.putArray("events").addObject()
                .put("skuId", "1001")
                .put("name", "iPhone")
                .put("cid2", 653)
                .put("cid2Name", "手机")
                .put("clicked", true)
                .put("pending", true)
                .put("screenNumber", 2);

        var verdict = judge.judge(
                MAPPER.createObjectNode(),
                MAPPER.createArrayNode(),
                SessionInterestJudge.interestsFrom(body.path("previous")),
                SessionInterestJudge.eventsFrom(body.path("events")));
        assertEquals(80, verdict.interests().stream()
                .filter(item -> item.cid2() == 653).findFirst().orElseThrow().interest());
        Interest device = verdict.interests().stream()
                .filter(item -> item.cid2() == 12345).findFirst().orElseThrow();
        assertEquals(85, device.interest());
        assertTrue(prompt.get().contains("智能设备"));
        assertTrue(prompt.get().contains("85"));
    }

    @Test
    void skipPressureDropsUnclickedCategoryFast() {
        var judge = new SessionInterestJudge(MAPPER, capturingModel(new AtomicReference<>(), """
                {"interests":[
                  {"cid2":653,"cid2Name":"手机","interest":90,"confidence":80},
                  {"cid2":830,"cid2Name":"手机配件","interest":90,"confidence":80}
                ],"summary":"test"}
                """), "test-model");
        var verdict = judge.judge(
                MAPPER.createObjectNode(),
                MAPPER.createArrayNode(),
                List.of(),
                List.of(
                        new Event("1", "iPhone", "Apple", 653, "手机", "手机通讯", true, true, 1),
                        new Event("2", "iPhone 2", "Apple", 653, "手机", "手机通讯", false, true, 1),
                        new Event("3", "iPhone 3", "Apple", 653, "手机", "手机通讯", false, true, 1),
                        new Event("4", "iPhone 4", "Apple", 653, "手机", "手机通讯", false, true, 1),
                        new Event("5", "iPhone 5", "Apple", 653, "手机", "手机通讯", false, true, 1),
                        new Event("6", "壳", "京造", 830, "手机配件", "手机通讯", false, true, 1),
                        new Event("7", "膜", "京造", 830, "手机配件", "手机通讯", false, true, 1),
                        new Event("8", "线", "京造", 830, "手机配件", "手机通讯", false, true, 1)));
        assertEquals(54, verdict.interests().stream()
                .filter(item -> item.cid2() == 653).findFirst().orElseThrow().interest());
        assertEquals(0, verdict.interests().stream()
                .filter(item -> item.cid2() == 830).findFirst().orElseThrow().interest());
    }

    @Test
    void keepsPreviousScoreWhenNoNewEvidence() {
        var judge = new SessionInterestJudge(MAPPER, capturingModel(new AtomicReference<>(), """
                {"interests":[{"cid2":1318,"cid2Name":"羽毛球拍","interest":10,"confidence":10}],"summary":"test"}
                """), "test-model");
        var verdict = judge.judge(
                MAPPER.createObjectNode(),
                MAPPER.createArrayNode(),
                List.of(new Interest(1318, "羽毛球拍", "运动户外", 82, 70, "BYPASS_LLM", 1, 8)),
                List.of(
                        new Event("1", "拍", "川崎", 1318, "羽毛球拍", "运动户外", true, false, 1),
                        new Event("2", "拍2", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1),
                        new Event("3", "拍3", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1),
                        new Event("4", "拍4", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1),
                        new Event("5", "拍5", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1),
                        new Event("6", "拍6", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1),
                        new Event("7", "拍7", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1),
                        new Event("8", "拍8", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1)));
        assertEquals(82, verdict.interests().get(0).interest());
    }

    @Test
    void relatedCategoryInheritsSiblingInsteadOfResetting() {
        var judge = new SessionInterestJudge(MAPPER, capturingModel(new AtomicReference<>(), """
                {"interests":[
                  {"cid2":1318,"cid2Name":"羽毛球拍","interest":10,"confidence":10},
                  {"cid2":1319,"cid2Name":"羽毛球线","interest":5,"confidence":10}
                ],"summary":"test"}
                """), "test-model");
        var verdict = judge.judge(
                MAPPER.createObjectNode(),
                MAPPER.createArrayNode(),
                List.of(new Interest(1318, "羽毛球拍", "运动户外", 82, 70, "BYPASS_LLM", 1, 8)),
                List.of(
                        new Event("1", "拍", "川崎", 1318, "羽毛球拍", "运动户外", true, false, 1),
                        new Event("2", "拍2", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1),
                        new Event("3", "拍3", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1),
                        new Event("4", "拍4", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1),
                        new Event("5", "拍5", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1),
                        new Event("6", "拍6", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1),
                        new Event("7", "拍7", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1),
                        new Event("8", "拍8", "川崎", 1318, "羽毛球拍", "运动户外", false, false, 1),
                        new Event("9", "线", "川崎", 1319, "羽毛球线", "运动户外", false, true, 2),
                        new Event("10", "线2", "川崎", 1319, "羽毛球线", "运动户外", false, true, 2)));
        assertEquals(82, verdict.interests().stream()
                .filter(item -> item.cid2() == 1318).findFirst().orElseThrow().interest());
        assertEquals(58, verdict.interests().stream()
                .filter(item -> item.cid2() == 1319).findFirst().orElseThrow().interest());
    }

    private static ChatModel capturingModel(AtomicReference<String> prompt, String json) {
        return request -> {
            prompt.set(request.getInstructions().get(1).getText());
            return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        };
    }
}
