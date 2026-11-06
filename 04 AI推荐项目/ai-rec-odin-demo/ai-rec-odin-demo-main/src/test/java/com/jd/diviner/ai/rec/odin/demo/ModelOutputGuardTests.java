package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.agent.model.ModelGenerationException;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.ModelOutputGuard;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelOutputGuardTests {
    @Test
    void detectsRepetitionRegardlessOfChunkBoundaries() {
        String text = "需要重新分析这件商品的适用场景，然后重新检查同一个已经确认的事实。".repeat(80);
        for (int chunkSize : new int[] {1, 13, 128, text.length()}) {
            var guard = new ModelOutputGuard();
            var failure = assertThrows(ModelGenerationException.class, () -> {
                for (int offset = 0; offset < text.length(); offset += chunkSize) {
                    guard.content(text.substring(offset, Math.min(offset + chunkSize, text.length())));
                }
            });
            assertEquals("REPETITIVE_CONTENT", failure.reason());
            assertFalse(failure.getMessage().contains("这件商品"));
        }
    }

    @Test
    void ordinarySharedJsonKeysAndQuotesAreNotRepetition() {
        var guard = new ModelOutputGuard();
        for (int index = 0; index < 4; index++) {
            guard.content("{\"productId\":\"sku-" + index + "\",\"strengths\":[],\"limitations\":[],\"fit\":\"适合日常使用\",\"uncertainty\":\"购买前核实\"}");
        }
        guard.content(SkillEngineTests.FINAL);
        var otherRun = new ModelOutputGuard();
        otherRun.content(SkillEngineTests.FINAL);
    }

    @Test
    void reasoningAndArgumentsHaveIndependentBounds() {
        var reasoning = new ModelOutputGuard();
        assertEquals("REPETITIVE_REASONING", assertThrows(ModelGenerationException.class,
                () -> reasoning.reasoning("private reasoning that repeats without useful new information. ".repeat(50))).reason());
        var arguments = new ModelOutputGuard();
        assertEquals("TOOL_ARGUMENT_LIMIT", assertThrows(ModelGenerationException.class,
                () -> arguments.toolArguments("x".repeat(65537))).reason());
        var content = new ModelOutputGuard();
        StringBuilder unique = new StringBuilder();
        for (int index = 0; index < 10000; index++) unique.append("不同段落-").append(index).append(';');
        assertEquals("CONTENT_LIMIT", assertThrows(ModelGenerationException.class,
                () -> content.content(unique.toString())).reason());
    }
}
