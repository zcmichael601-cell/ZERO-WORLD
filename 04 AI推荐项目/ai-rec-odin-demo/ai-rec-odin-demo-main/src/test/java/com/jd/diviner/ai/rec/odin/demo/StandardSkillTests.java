package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class StandardSkillTests {
    private static final String SKILL = "shopping-recommendation";
    private static final String SEARCH = "{\"interests\":[\"跑步\"],\"budget\":800,\"limit\":6}";
    private static final String KNOWLEDGE = "{\"productIds\":[\"SKU-001\"],\"question\":\"适用场景\"}";

    static JsonNode facts(Prompt prompt) {
        try { return SkillEngineTests.MAPPER.readTree(prompt.getInstructions().get(1).getText()); }
        catch (Exception exception) { return SkillEngineTests.MAPPER.createObjectNode(); }
    }

    private SkillEngine engine(ChatModel model) { return engine(model, 8); }

    private SkillEngine engine(ChatModel model, int maxTools) {
        return engine(model, maxTools, 8);
    }

    private SkillEngine engine(ChatModel model, int maxTools, int maxRounds) {
        var registry = SkillEngineTests.registry();
        var resource = new ClassPathResource("skills/recommendation.json") {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                try (var stream = super.getInputStream()) {
                    var root = SkillEngineTests.MAPPER.readTree(stream);
                    var skill = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("skills").get(0);
                    assertEquals("AGENT", skill.path("executionMode").asText());
                    skill.put("maxToolCalls", maxTools);
                    skill.put("maxModelCalls", maxRounds);
                    return new java.io.ByteArrayInputStream(SkillEngineTests.MAPPER.writeValueAsBytes(root));
                }
            }
        };
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, resource, registry);
        return new SkillEngine(SkillEngineTests.MAPPER, catalog, registry, new SkillModelClient(model, "test-model"));
    }

    private static ChatResponse batch(String... names) {
        var calls = java.util.stream.IntStream.range(0, names.length).mapToObj(index ->
                new AssistantMessage.ToolCall("batch-" + index, "function", names[index], "{}")).toList();
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
    }

    private static ChatResponse load() { return SkillEngineTests.tool("load", "Skill", "{\"command\":\"shopping-recommendation\"}"); }
    private static ChatResponse search(String id, String args) { return SkillEngineTests.tool(id, "search_products", args); }
    private static ChatResponse knowledge(String id, String args) { return SkillEngineTests.tool(id, "retrieve_product_knowledge", args); }
    private static List<String> available(Prompt prompt) {
        return ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream().map(tool -> tool.getToolDefinition().name()).toList();
    }

    @Test
    void defaultAgentLoadsSkillThroughModelAndChoosesUserQueryOrder() {
        for (String mode : List.of("REAL", "DEMO")) {
            var calls = new AtomicInteger();
            var progress = new ArrayList<SkillEngine.Progress>();
            var run = engine(prompt -> {
                return switch (calls.incrementAndGet()) {
                    case 1 -> {
                        assertEquals(List.of("Skill"), available(prompt));
                        assertFalse(progress.stream().anyMatch(event -> "TOOL_STARTED".equals(event.type())));
                        yield load();
                    }
                    case 2 -> {
                        assertEquals(List.of("get_user_profile", "get_user_behaviors"), available(prompt));
                        yield batch("get_user_behaviors", "get_user_profile");
                    }
                    case 3 -> search("search", SEARCH);
                    case 4 -> knowledge("knowledge", KNOWLEDGE);
                    default -> SkillEngineTests.answer(SkillEngineTests.FINAL);
                };
            }).execute(SKILL, SkillEngineTests.MAPPER.createObjectNode().put("username", "xiaoming").put("userDataMode", mode), progress::add, () -> false);
            assertEquals("SUCCESS", run.status(), run.error());
            assertEquals(5, run.modelCalls());
            assertEquals(List.of("Skill", "get_user_behaviors", "get_user_profile", "search_products", "retrieve_product_knowledge"),
                    run.traces().stream().map(SkillEngine.Trace::tool).toList());
            assertTrue(run.traces().stream().allMatch(trace -> trace.modelRound() > 0));
        }
    }

    @Test
    void modelCanSearchAgainAfterReadingKnowledgeAndRetainsEarlierResults() {
        var calls = new AtomicInteger();
        var run = engine(prompt -> switch (calls.incrementAndGet()) {
            case 1 -> load();
            case 2 -> batch("get_user_profile", "get_user_behaviors");
            case 3 -> search("first-search", SEARCH);
            case 4 -> knowledge("first-knowledge", KNOWLEDGE);
            case 5 -> {
                assertTrue(available(prompt).contains("search_products"));
                yield search("second-search", SEARCH.replace("跑步", "户外"));
            }
            case 6 -> {
                assertFalse(available(prompt).contains("search_products"));
                assertTrue(prompt.getInstructions().stream()
                        .filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
                        .map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast)
                        .flatMap(message -> message.getResponses().stream())
                        .anyMatch(response -> response.responseData().contains("SKU-001")));
                yield knowledge("second-knowledge", KNOWLEDGE.replace("SKU-001", "SKU-002"));
            }
            default -> SkillEngineTests.answer(SkillEngineTests.FINAL);
        }).execute(SKILL, SkillProgressTests.input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(7, run.modelCalls());
        assertEquals(7, run.traces().size());
        assertEquals(2, run.traces().stream().filter(trace -> trace.tool().equals("search_products")).count());
        assertEquals(2, run.traces().stream().filter(trace -> trace.tool().equals("retrieve_product_knowledge")).count());
    }

    @Test
    void identicalSearchWithReorderedObjectKeysIsNotExecutedTwice() {
        var calls = new AtomicInteger();
        var run = engine(prompt -> switch (calls.incrementAndGet()) {
            case 1 -> load();
            case 2 -> batch("get_user_profile", "get_user_behaviors");
            case 3 -> search("first", SEARCH);
            case 4 -> search("duplicate", "{\"limit\":6,\"budget\":800,\"interests\":[\"跑步\"]}");
            case 5 -> {
                assertTrue(prompt.getContents().contains("参数完全相同"));
                yield knowledge("knowledge", KNOWLEDGE);
            }
            default -> SkillEngineTests.answer(SkillEngineTests.FINAL);
        }).execute(SKILL, SkillProgressTests.input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(6, run.modelCalls());
        assertEquals(5, run.traces().size());
        assertFalse(run.traces().stream().anyMatch(trace -> trace.id().equals("duplicate")));
    }

    @Test
    void batchedSearchesAreReviewedBeforeAnyToolOrBudgetIsConsumed() {
        var calls = new AtomicInteger();
        var run = engine(prompt -> switch (calls.incrementAndGet()) {
            case 1 -> load();
            case 2 -> batch("get_user_profile", "get_user_behaviors");
            case 3 -> {
                var repeated = java.util.stream.IntStream.range(0, 10).mapToObj(index ->
                        new AssistantMessage.ToolCall("repeated-" + index, "function", "search_products", SEARCH)).toList();
                yield new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(repeated).build())));
            }
            case 4 -> search("search", SEARCH);
            case 5 -> knowledge("knowledge", KNOWLEDGE);
            default -> SkillEngineTests.answer(SkillEngineTests.FINAL);
        }).execute(SKILL, SkillProgressTests.input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(5, run.traces().size());
        assertEquals(6, run.modelCalls());
    }

    @Test
    void remainingToolBudgetReservesKnowledgeAndAllowsFinalAnswer() {
        var calls = new AtomicInteger();
        var run = engine(prompt -> switch (calls.incrementAndGet()) {
            case 1 -> load();
            case 2 -> batch("get_user_profile", "get_user_behaviors");
            case 3 -> search("search", SEARCH);
            case 4 -> {
                assertEquals(List.of("retrieve_product_knowledge"), available(prompt));
                yield search("over-budget", SEARCH.replace("跑步", "户外"));
            }
            case 5 -> knowledge("knowledge", KNOWLEDGE);
            default -> SkillEngineTests.answer(SkillEngineTests.FINAL);
        }, 5).execute(SKILL, SkillProgressTests.input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(5, run.traces().size());
    }

    @Test
    void persistentDuplicatesStopAtBoundedReplanningWithoutRepeatedTools() {
        var calls = new AtomicInteger();
        var run = engine(prompt -> switch (calls.incrementAndGet()) {
            case 1 -> load();
            case 2 -> batch("get_user_profile", "get_user_behaviors");
            default -> search("search-" + calls.get(), SEARCH);
        }).execute(SKILL, SkillProgressTests.input());
        assertEquals("FAILED", run.status());
        assertEquals(6, run.modelCalls());
        assertEquals(4, run.traces().size());
        assertTrue(run.error().contains("未重复执行工具"));
    }

    @Test
    void emptyFollowupSearchRetainsCandidatesForKnowledgeAndRecommendation() {
        var calls = new AtomicInteger();
        var run = engine(prompt -> switch (calls.incrementAndGet()) {
            case 1 -> load();
            case 2 -> batch("get_user_profile", "get_user_behaviors");
            case 3 -> search("first", SEARCH);
            case 4 -> search("empty", SEARCH.replace("800", "1"));
            case 5 -> {
                var responses = prompt.getInstructions().stream()
                        .filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
                        .map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast).toList();
                var response = responses.get(responses.size() - 1);
                try {
                    var result = SkillEngineTests.MAPPER.readTree(response.getResponses().get(0).responseData());
                    assertEquals(0, result.path("currentSearchProductCount").asInt(-1));
                    assertTrue(result.path("products").size() > 0);
                    assertEquals(result.path("products").size(), result.path("accumulatedCandidateCount").asInt());
                } catch (java.io.IOException exception) { throw new AssertionError(exception); }
                yield knowledge("knowledge", KNOWLEDGE);
            }
            default -> SkillEngineTests.answer(SkillEngineTests.FINAL);
        }).execute(SKILL, SkillProgressTests.input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(6, run.traces().size());
        assertEquals("SKU-001", run.output().at("/products/0/product/id").asText());
    }

    @Test
    void roundBudgetKeepsKnowledgeAndFinalAnswerInsteadOfOptionalSearch() {
        var calls = new AtomicInteger();
        var run = engine(prompt -> switch (calls.incrementAndGet()) {
            case 1 -> load();
            case 2 -> SkillEngineTests.tool("profile", "get_user_profile", "{}");
            case 3 -> SkillEngineTests.tool("behaviors", "get_user_behaviors", "{}");
            case 4 -> search("search", SEARCH);
            case 5 -> {
                assertEquals(List.of("retrieve_product_knowledge"), available(prompt));
                yield knowledge("knowledge", KNOWLEDGE);
            }
            default -> {
                assertTrue(available(prompt).isEmpty());
                yield SkillEngineTests.answer(SkillEngineTests.FINAL);
            }
        }, 8, 6).execute(SKILL, SkillProgressTests.input());
        assertEquals("SUCCESS", run.status(), run.error());
        assertEquals(6, run.modelCalls());
        assertEquals(5, run.traces().size());
    }

    @Test
    void answerAndCitationRepairsNeverReplayAgentTools() {
        String badCitation = SkillEngineTests.FINAL.replace("INSUFFICIENT_EVIDENCE", "PARTIAL_EVIDENCE")
                .replace("\"strengths\":[]", "\"strengths\":[{\"text\":\"待核实描述\",\"evidenceId\":\"wrong\",\"quote\":\"不存在的原文引用\"}]");
        for (String invalid : List.of("not-json", badCitation, badCitation.replace("\"text\":\"待核实描述\",", ""))) {
            var calls = new AtomicInteger();
            var run = engine(prompt -> switch (calls.incrementAndGet()) {
                case 1 -> load();
                case 2 -> batch("get_user_profile", "get_user_behaviors");
                case 3 -> search("search", SEARCH);
                case 4 -> knowledge("knowledge", KNOWLEDGE);
                case 5 -> SkillEngineTests.answer(invalid);
                default -> {
                    assertEquals("FINAL_ANSWER", facts(prompt).path("phase").asText());
                    yield SkillEngineTests.answer(SkillEngineTests.FINAL);
                }
            }).execute(SKILL, SkillProgressTests.input());
            assertEquals("SUCCESS", run.status(), run.error());
            assertEquals(6, run.modelCalls());
            assertEquals(5, run.traces().size());
        }
    }
}
