package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.agent.rag.RagKnowledgeBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RagTests {

    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private TestEmbedding embedding;
    private RagKnowledgeBase knowledgeBase;
    private static final String ANSWER = "{\"answerable\":true,\"answer\":\"背包只能防泼水 [C1]。\",\"citations\":[\"C1\"]}";

    @BeforeEach
    void setup() throws Exception {
        Files.writeString(directory.resolve("bag.md"), "# 背包手册\n\n这款背包只能防泼水，不可浸水。随包附带防雨罩，下雨时应使用防雨罩，电脑应另装防水袋。支持最大14英寸电脑。", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("air.md"), "# 耳机手册\n\n这款耳机关闭降噪后能播放8小时，开启降噪后能播放5小时。耳机不支持无线充电，使用USB-C充电盒充电。", StandardCharsets.UTF_8);
        embedding = new TestEmbedding();
        knowledgeBase = new RagKnowledgeBase(embedding, "test-embedding", new PathMatchingResourcePatternResolver(),
                directory.toUri() + "*.md", 2, 0.5);
    }

    @Test
    void buildsActualVectorIndexAndRetrievesOnlyRelevantChunks() {
        assertFalse(knowledgeBase.status().ready());
        assertEquals(0, embedding.calls.get());
        var status = knowledgeBase.rebuild();
        assertTrue(status.ready());
        assertEquals(2, status.documents().size());
        assertEquals(status.chunks() + 1, embedding.calls.get());
        var found = knowledgeBase.retrieve("背包防水吗");
        assertEquals(1, found.sources().size());
        assertEquals("bag.md", found.sources().get(0).source());
        assertEquals("C1", found.sources().get(0).id());
        assertEquals(1.0, found.sources().get(0).score(), 0.00001);
        assertTrue(found.sources().get(0).text().contains("防雨罩"));
        assertEquals(status.version(), found.version());
        assertTrue(knowledgeBase.retrieve("外星飞船").sources().isEmpty());
    }

    @Test
    void splitsLongDocumentsAndRebuildDoesNotAccumulateDuplicates() throws Exception {
        Files.writeString(directory.resolve("bag.md"), "# 背包手册\n\n" + "背包只能防泼水，不能浸水。电脑需要单独防水袋。\n\n".repeat(45));
        var first = knowledgeBase.rebuild();
        assertTrue(first.chunks() > first.documents().size());
        var chunks = knowledgeBase.retrieve("背包").sources().stream().map(RagKnowledgeBase.Evidence::chunkId).sorted().toList();
        var second = knowledgeBase.rebuild();
        assertEquals(first.chunks(), second.chunks());
        assertNotEquals(first.version(), second.version());
        assertEquals(chunks, knowledgeBase.retrieve("背包").sources().stream().map(RagKnowledgeBase.Evidence::chunkId).sorted().toList());
    }

    @Test
    void failedRebuildKeepsPreviousCompleteSnapshot() {
        var original = knowledgeBase.rebuild();
        embedding.fail.set(true);
        assertThrows(IllegalStateException.class, knowledgeBase::rebuild);
        assertEquals(original.version(), knowledgeBase.status().version());
        assertFalse(knowledgeBase.status().indexing());
        embedding.fail.set(false);
        assertEquals(original.version(), knowledgeBase.retrieve("背包").version());
    }

    @Test
    void concurrentRebuildIsRejectedAndQueriesKeepUsingOldIndex() throws Exception {
        var original = knowledgeBase.rebuild();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        embedding.beforeDocument = () -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(knowledgeBase::rebuild);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(RagKnowledgeBase.NotReadyException.class, knowledgeBase::rebuild);
            assertEquals(original.version(), knowledgeBase.retrieve("背包").version());
            release.countDown();
            assertNotEquals(original.version(), pending.get(5, TimeUnit.SECONDS).version());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void missingIndexAndInvalidConfigurationAreExplicitErrors() {
        assertThrows(RagKnowledgeBase.NotReadyException.class, () -> knowledgeBase.retrieve("背包"));
        assertThrows(IllegalArgumentException.class, () -> new RagKnowledgeBase(embedding, "test",
                new PathMatchingResourcePatternResolver(), "https://invalid/doc.md", 2, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new RagKnowledgeBase(embedding, "test",
                new PathMatchingResourcePatternResolver(), "classpath:knowledge/*.md", 0, Double.NaN));
    }

    @Test
    void emptyOrOversizedDocumentsCannotReplaceIndex() throws Exception {
        var original = knowledgeBase.rebuild();
        Files.writeString(directory.resolve("empty.md"), " ");
        assertThrows(IllegalArgumentException.class, knowledgeBase::rebuild);
        Files.writeString(directory.resolve("empty.md"), "a".repeat(16385));
        assertThrows(IllegalArgumentException.class, knowledgeBase::rebuild);
        assertEquals(original.version(), knowledgeBase.status().version());
    }

    private static class TestEmbedding extends AbstractEmbeddingModel {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean fail = new AtomicBoolean();
        Runnable beforeDocument = () -> {};
        public float[] embed(Document document) { beforeDocument.run(); return embed(document.getText()); }
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (fail.get()) throw new IllegalStateException("embedding unavailable");
            calls.incrementAndGet();
            List<Embedding> values = new ArrayList<>();
            for (int index = 0; index < request.getInstructions().size(); index++) {
                String text = request.getInstructions().get(index);
                float[] vector = text.contains("背包") ? new float[] {1, 0, 0} : text.contains("耳机") ? new float[] {0, 1, 0} : new float[] {0, 0, 1};
                values.add(new Embedding(vector, index));
            }
            return new EmbeddingResponse(values);
        }
    }
}
