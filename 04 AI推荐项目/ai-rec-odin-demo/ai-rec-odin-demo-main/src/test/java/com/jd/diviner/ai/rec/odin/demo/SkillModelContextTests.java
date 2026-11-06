package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillModelContext;
import com.jd.diviner.ai.rec.odin.demo.app.application.commerce.RecommendationModelContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.*;

class SkillModelContextTests {

    @Test
    void searchKeepsDecisionFieldsWithoutMutatingFullProductFacts() throws Exception {
        var original = SkillEngineTests.MAPPER.readTree("""
                {"notice":"非实时报价","budgetKnown":false,"products":[{"id":"100017657298","name":"商品",
                "description":"简介","price":299,"currency":"CNY","priceNotice":"采集参考价",
                "stockNotice":"下单前核实","availability":"UNKNOWN","selectedOptions":"黑色",
                "sourceUrl":"https://item.jd.com/100017657298.html","capturedAt":"2026-10-28T00:00:00Z",
                "specifications":"完整参数","services":"页面服务说明","icon":"icon","discoveryUrl":"url"}]}
                """);
        var copy = original.deepCopy();
        var projected = RecommendationModelContext.toolData("shopping-recommendation", "search_products", original);
        assertEquals(copy, original);
        for (String field : List.of("id", "name", "description", "price", "currency", "priceNotice", "stockNotice",
                "availability", "selectedOptions", "sourceUrl", "capturedAt")) {
            assertEquals(original.at("/products/0/" + field), projected.at("/products/0/" + field));
        }
        assertEquals(original.path("notice"), projected.path("notice"));
        assertFalse(projected.path("budgetKnown").asBoolean());
        assertFalse(projected.at("/products/0").has("specifications"));
        assertTrue(projected.toString().length() < original.toString().length());
    }

    @Test
    void knowledgePreservesEveryEvidenceIdAndEntireQuoteText() throws Exception {
        var original = SkillEngineTests.MAPPER.readTree("""
                {"products":[{"productId":"100017657298","version":"v1","status":"FOUND","evidence":[
                {"id":"K-1","productId":"100017657298","text":"不适合涉水使用，需确认规格。","source":"sku.md","title":"商品"},
                {"id":"K-2","productId":"100017657298","text":"页面标注适用于日常办公。","source":"sku.md","title":"商品"}]}]}
                """);
        var projected = RecommendationModelContext.toolData("shopping-recommendation", "retrieve_product_knowledge", original);
        assertEquals(2, projected.at("/products/0/evidence").size());
        for (int index = 0; index < 2; index++) {
            for (String field : List.of("id", "productId", "text")) {
                String pointer = "/products/0/evidence/" + index + "/" + field;
                assertEquals(original.at(pointer), projected.at(pointer));
            }
        }
        assertEquals("v1", projected.at("/products/0/version").asText());
        assertEquals("FOUND", projected.at("/products/0/status").asText());
        assertTrue(original.at("/products/0/evidence/0").has("source"));
        assertFalse(projected.at("/products/0/evidence/0").has("source"));
    }

    @Test
    void memoryRemovesOnlyDerivedDuplicatesAndRetainsTypesTimesAndIdentity() throws Exception {
        ObjectNode original = (ObjectNode) SkillEngineTests.MAPPER.readTree("""
                {"source":"AI_MEMORY","username":"315c70c8f4a25681","budget":null,"dataStatus":"AVAILABLE",
                "memories":[{"memory":"浏览跑鞋","create_time":"2026-10-28","metadata":{"memory_subtype2":"4.1"}}],
                "interests":["浏览跑鞋"],"signals":["[原始时间 2026-10-28] 浏览跑鞋"]}
                """);
        for (String name : List.of("get_user_profile", "get_user_behaviors")) {
            var projected = RecommendationModelContext.toolData("shopping-recommendation", name, original);
            for (String field : List.of("memories", "username", "budget", "dataStatus")) assertEquals(original.get(field), projected.get(field));
            assertFalse(projected.has(name.equals("get_user_profile") ? "interests" : "signals"));
        }
        assertTrue(original.has("interests"));
        assertTrue(original.has("signals"));
        original.putArray("memories");
        assertEquals(original, RecommendationModelContext.toolData("shopping-recommendation", "get_user_profile", original));
    }

    @Test
    void otherSkillsDemoProfilesAndUnknownToolsKeepTheirPayloads() throws Exception {
        var original = SkillEngineTests.MAPPER.readTree("""
                {"products":[{"specifications":"完整参数"}],"interests":["跑步"],"signals":["浏览跑鞋"]}
                """);
        assertEquals(original, RecommendationModelContext.toolData("beijing-weather-travel", "search_products", original));
        assertEquals(original, RecommendationModelContext.toolData("shopping-recommendation", "get_user_profile", original));
        assertEquals(original, RecommendationModelContext.toolData("shopping-recommendation", "get_user_behaviors", original));
        assertEquals(original, RecommendationModelContext.toolData("shopping-recommendation", "other", original));
    }

    @Test
    void contextSizeIncludesToolResponsesArgumentsAndDefinitions() {
        var assistant = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-id", "function", "test", "{\"key\":123}"))).build();
        var response = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call-id", "test", "{\"result\":456}"))).build();
        ToolCallback callback = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("test").description("description").inputSchema("{}").build();
            }
            public String call(String arguments) { throw new AssertionError("Must not execute tools to measure context"); }
        };
        var counts = SkillModelContext.measure(List.of(new SystemMessage("system"), assistant, response), List.of(callback));
        assertEquals("{\"key\":123}".length(), counts.toolArgumentChars());
        assertEquals("{\"result\":456}".length(), counts.toolResponseChars());
        assertEquals("testdescription{}".length(), counts.toolSchemaChars());
        assertEquals(counts.textChars() + counts.toolResponseChars() + counts.toolArgumentChars(), counts.messageChars());
        assertEquals(counts.messageChars() + counts.toolSchemaChars(), counts.payloadChars());
    }
}
