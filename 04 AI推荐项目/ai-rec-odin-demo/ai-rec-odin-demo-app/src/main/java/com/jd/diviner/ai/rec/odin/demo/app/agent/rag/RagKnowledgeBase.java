package com.jd.diviner.ai.rec.odin.demo.app.agent.rag;

import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

public class RagKnowledgeBase {

    public static final int MAX_DOCUMENTS = 2000;
    public static final int MAX_CHUNKS = 16384;
    public static final int MAX_TOTAL_BYTES = 32 * 1024 * 1024;

    private final EmbeddingModel embedding;
    private final String embeddingModel;
    private final ResourceSource resources;
    private final int topK;
    private final double threshold;
    private final AtomicBoolean indexing = new AtomicBoolean();
    private volatile Snapshot snapshot;

    public RagKnowledgeBase(EmbeddingModel embedding, String embeddingModel, ResourcePatternResolver resources,
                            String location, int topK, double threshold) {
        this(embedding, embeddingModel, localResources(resources, location), topK, threshold);
    }

    private static ResourceSource localResources(ResourcePatternResolver resources, String location) {
        if (!(location.startsWith("classpath:") || location.startsWith("classpath*:") || location.startsWith("file:"))) {
            throw new IllegalArgumentException("RAG 资料仅支持本地 classpath: 或 file: 资源");
        }
        return () -> resources.getResources(location);
    }

    @FunctionalInterface
    public interface ResourceSource { Resource[] load() throws IOException; }

    public RagKnowledgeBase(EmbeddingModel embedding, String embeddingModel, ResourceSource resources,
                            int topK, double threshold) {
        if (topK < 1 || topK > 8 || !Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("RAG top-k 必须为 1~8，similarity-threshold 必须为 0~1");
        }
        this.embedding = embedding;
        this.embeddingModel = embeddingModel;
        this.resources = resources;
        this.topK = topK;
        this.threshold = threshold;
    }

    public Status status() {
        Snapshot current = snapshot;
        return new Status(current != null, indexing.get(), embeddingModel, "SimpleVectorStore（进程内存）", topK, threshold,
                current == null ? List.of() : current.documents(), current == null ? 0 : current.chunks(),
                current == null ? null : current.indexedAt(), current == null ? null : current.version());
    }

    public Status rebuild() {
        long start = System.nanoTime();
        ExecutionLog log = ExecutionLog.current().with("ragOperationId", UUID.randomUUID().toString(), "channel", "RAG");
        try (var scope = log.activate()) {
            log.info("RAG_INDEX_STARTED", "embeddingModel", embeddingModel, "previousIndexReady", snapshot != null);
            Status result = rebuildIndex();
            log.info("RAG_INDEX_COMPLETED", "elapsedMs", ExecutionLog.elapsed(start), "documents", result.documents().size(),
                    "chunks", result.chunks(), "indexVersion", result.version());
            return result;
        } catch (RuntimeException exception) {
            log.failure("RAG_INDEX_FAILED", exception, "elapsedMs", ExecutionLog.elapsed(start));
            throw exception;
        }
    }

    private Status rebuildIndex() {
        if (!indexing.compareAndSet(false, true)) throw new NotReadyException("知识库正在构建，请稍后再试");
        try {
            long documentStart = System.nanoTime();
            ExecutionLog.current().info("RAG_DOCUMENT_LOAD_STARTED", "message", "正在读取商品资料并切分，尚未调用向量模型");
            Resource[] files = resources.load();
            if (files.length == 0 || files.length > MAX_DOCUMENTS) throw new IllegalArgumentException("知识库需要 1~" + MAX_DOCUMENTS + " 个本地 Markdown 文件");
            Arrays.sort(files, Comparator.comparing(resource -> String.valueOf(resource.getFilename())));
            List<Document> chunks = new ArrayList<>();
            List<SourceDocument> documents = new ArrayList<>();
            int totalBytes = 0;
            var filenames = new java.util.HashSet<String>();
            var splitter = new TokenTextSplitter(256, 80, 10, 10000, true);
            for (Resource file : files) {
                String filename = file.getFilename();
                if (filename == null || !filename.endsWith(".md")) throw new IllegalArgumentException("知识库只支持 .md 文件");
                if (!filenames.add(filename)) throw new IllegalArgumentException("知识文档文件名不能重复，避免 SKU 过滤混淆");
                byte[] bytes;
                try (var stream = file.getInputStream()) { bytes = stream.readNBytes(16385); }
                if (bytes.length > 16384) throw new IllegalArgumentException("单个知识库文件不得超过 16 KB");
                totalBytes += bytes.length;
                if (totalBytes > MAX_TOTAL_BYTES) throw new IllegalArgumentException("知识库原文总量不得超过 32 MB");
                String text = new String(bytes, StandardCharsets.UTF_8).strip();
                if (text.isBlank()) throw new IllegalArgumentException("知识库不能包含空文件");
                String title = text.lines().findFirst().orElse(filename).replaceFirst("^#+\\s*", "");
                String documentId = UUID.nameUUIDFromBytes((filename + "\n" + text).getBytes(StandardCharsets.UTF_8)).toString();
                List<Document> pieces = splitter.apply(List.of(new Document(text)));
                if (pieces.isEmpty()) throw new IllegalArgumentException("知识库文档过短，无法切分");
                if (chunks.size() + pieces.size() > MAX_CHUNKS) throw new IllegalArgumentException("知识库最多支持 " + MAX_CHUNKS + " 个片段");
                for (int index = 0; index < pieces.size(); index++) {
                    chunks.add(Document.builder().id(documentId + "-" + (index + 1)).text(pieces.get(index).getText())
                            .metadata(Map.of("source", filename, "title", title, "chunk", index + 1)).build());
                }
                documents.add(new SourceDocument(filename, title, pieces.size()));
                if (documents.size() % 100 == 0 || documents.size() == files.length) {
                    ExecutionLog.current().info("RAG_DOCUMENT_LOAD_PROGRESS", "processedDocuments", documents.size(),
                            "totalDocuments", files.length, "chunks", chunks.size(), "sourceBytes", totalBytes,
                            "elapsedMs", ExecutionLog.elapsed(documentStart));
                }
            }
            ExecutionLog.current().info("RAG_DOCUMENT_LOAD_COMPLETED", "documents", documents.size(), "chunks", chunks.size(),
                    "sourceBytes", totalBytes, "elapsedMs", ExecutionLog.elapsed(documentStart));
            SimpleVectorStore store = SimpleVectorStore.builder(embedding).build();
            long vectorStart = System.nanoTime();
            ExecutionLog.current().info("RAG_VECTOR_ADD_STARTED", "documents", documents.size(), "chunks", chunks.size(),
                    "sourceBytes", totalBytes, "embeddingModel", embeddingModel);
            for (int offset = 0; offset < chunks.size(); offset += 32) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("RAG 索引构建被中断");
                int processed = Math.min(offset + 32, chunks.size());
                long batchStart = System.nanoTime();
                var batchLog = ExecutionLog.current().with("batchStart", offset + 1, "batchEnd", processed, "totalChunks", chunks.size());
                try (var scope = batchLog.activate()) {
                    batchLog.info("RAG_VECTOR_BATCH_STARTED", "message", "正在调用向量服务构建当前批次", "completedBatchChunks", offset);
                    store.add(chunks.subList(offset, processed));
                    batchLog.info("RAG_VECTOR_BATCH_COMPLETED", "elapsedMs", ExecutionLog.elapsed(batchStart), "processedChunks", processed);
                } catch (RuntimeException exception) {
                    batchLog.failure("RAG_VECTOR_BATCH_FAILED", exception, "completedBatchChunks", offset, "elapsedMs", ExecutionLog.elapsed(batchStart),
                            "failureSummary", RagEmbeddingTransport.failureSummary(exception));
                    throw exception;
                }
                long elapsed = ExecutionLog.elapsed(vectorStart);
                ExecutionLog.current().info("RAG_INDEX_PROGRESS", "message", "正在构建知识库向量索引", "processedChunks", processed,
                        "totalChunks", chunks.size(), "progressPercent", processed * 100 / chunks.size(), "elapsedMs", elapsed,
                        "estimatedRemainingMs", elapsed * (chunks.size() - processed) / processed);
            }
            ExecutionLog.current().info("RAG_VECTOR_ADD_COMPLETED", "elapsedMs", ExecutionLog.elapsed(vectorStart), "chunks", chunks.size());
            snapshot = new Snapshot(store, List.copyOf(documents), List.copyOf(chunks), chunks.size(), Instant.now().toString(), UUID.randomUUID().toString());
        } catch (IOException exception) {
            throw new IllegalStateException("知识库文件读取失败", exception);
        } finally {
            indexing.set(false);
        }
        return status();
    }

    public Retrieval retrieve(String question) {
        return retrieve(question, null);
    }

    public void ensureReady() {
        long start = System.nanoTime();
        ExecutionLog.current().info("RAG_ENSURE_READY_STARTED", "ready", snapshot != null, "indexing", indexing.get());
        synchronized (this) {
            long lockWaitMs = ExecutionLog.elapsed(start);
            boolean reused = snapshot != null;
            if (!reused) rebuild();
            ExecutionLog.current().info("RAG_ENSURE_READY_COMPLETED", "reused", reused, "lockWaitMs", lockWaitMs,
                    "elapsedMs", ExecutionLog.elapsed(start), "indexVersion", snapshot.version());
        }
    }

    public Retrieval retrieve(String question, String source) {
        long start = System.nanoTime();
        ExecutionLog log = ExecutionLog.current().with("ragOperationId", UUID.randomUUID().toString(), "channel", "RAG");
        log.info("RAG_RETRIEVE_STARTED", "queryChars", question == null ? 0 : question.length(), "sourceFiltered", source != null,
                "embeddingModel", embeddingModel, "topK", topK, "threshold", threshold);
        try {
            Retrieval result = retrieveMatches(question, source);
            log.info("RAG_RETRIEVE_COMPLETED", "elapsedMs", ExecutionLog.elapsed(start), "hits", result.sources().size(),
                    "indexVersion", result.version());
            return result;
        } catch (RuntimeException exception) {
            log.failure("RAG_RETRIEVE_FAILED", exception, "elapsedMs", ExecutionLog.elapsed(start));
            throw exception;
        }
    }

    private Retrieval retrieveMatches(String question, String source) {
        return retrieveMatches(question, source, topK, threshold);
    }

    public Retrieval discoverProducts(String question) {
        long start = System.nanoTime();
        ExecutionLog log = ExecutionLog.current().with("ragOperationId", UUID.randomUUID().toString(), "channel", "RAG");
        log.info("RAG_PRODUCT_DISCOVERY_STARTED", "queryChars", question == null ? 0 : question.length(), "topK", 64, "threshold", 0.5);
        try {
            Retrieval result = retrieveMatches(question, null, 64, 0.5);
            log.info("RAG_PRODUCT_DISCOVERY_COMPLETED", "hits", result.sources().size(),
                    "elapsedMs", ExecutionLog.elapsed(start), "indexVersion", result.version());
            return result;
        } catch (RuntimeException exception) {
            log.failure("RAG_PRODUCT_DISCOVERY_FAILED", exception, "elapsedMs", ExecutionLog.elapsed(start));
            throw exception;
        }
    }

    public Retrieval sourceEvidence(List<String> sources) {
        Snapshot current = snapshot;
        if (current == null) throw new NotReadyException("请先构建知识库索引");
        List<Evidence> evidence = current.sourceChunks().stream()
                .filter(chunk -> sources.contains(chunk.getMetadata().get("source").toString()))
                .map(chunk -> new Evidence("K-" + chunk.getId(), chunk.getId(), chunk.getMetadata().get("source").toString(),
                        chunk.getMetadata().get("title").toString(), ((Number) chunk.getMetadata().get("chunk")).intValue(),
                        null, chunk.getText())).toList();
        ExecutionLog.current().info("RAG_SOURCE_EXPANSION_COMPLETED", "sources", sources.size(), "chunks", evidence.size(),
                "indexVersion", current.version());
        return new Retrieval(current.version(), evidence);
    }

    private Retrieval retrieveMatches(String question, String source, int resultLimit, double minimumScore) {
        Snapshot current = snapshot;
        if (current == null) throw new NotReadyException("请先构建知识库索引");
        var builder = VectorStoreDocumentRetriever.builder().vectorStore(current.store()).topK(resultLimit)
                .similarityThreshold(minimumScore);
        if (source != null) builder.filterExpression(new FilterExpressionBuilder().eq("source", source).build());
        var retriever = builder.build();
        List<Document> matches = retriever.retrieve(new Query(question));
        List<Evidence> evidence = new ArrayList<>();
        for (int index = 0; index < matches.size(); index++) {
            Document match = matches.get(index);
            evidence.add(new Evidence("C" + (index + 1), match.getId(), match.getMetadata().get("source").toString(),
                    match.getMetadata().get("title").toString(), ((Number) match.getMetadata().get("chunk")).intValue(),
                    match.getScore(), match.getText()));
        }
        return new Retrieval(current.version(), List.copyOf(evidence));
    }

    private record Snapshot(SimpleVectorStore store, List<SourceDocument> documents, List<Document> sourceChunks,
                            int chunks, String indexedAt, String version) {}
    public record SourceDocument(String source, String title, int chunks) {}
    public record Status(boolean ready, boolean indexing, String embeddingModel, String store, int topK, double threshold,
                         List<SourceDocument> documents, int chunks, String indexedAt, String version) {}
    public record Evidence(String id, String chunkId, String source, String title, int chunk, Double score, String text) {}
    public record Retrieval(String version, List<Evidence> sources) {}
    public static class NotReadyException extends RuntimeException {
        public NotReadyException(String message) { super(message); }
    }
}
