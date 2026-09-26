package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.IndexedChunk;
import com.jijing.fund.knowledge.domain.IngestionStatus;
import com.jijing.fund.knowledge.domain.KnowledgeJobWorkItem;
import com.jijing.fund.knowledge.domain.ParsedDocument;
import com.jijing.fund.knowledge.port.DocumentEmbeddingPort;
import com.jijing.fund.knowledge.port.DocumentMetadataRepository;
import com.jijing.fund.knowledge.port.DocumentParser;
import com.jijing.fund.knowledge.port.DocumentSearchIndex;
import com.jijing.fund.knowledge.port.KnowledgeCheckpointStore;
import com.jijing.fund.knowledge.port.KnowledgeJobRepository;
import com.jijing.fund.knowledge.port.RawDocumentStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 执行一条已租约的入库任务，并按检查点从解析、切块或向量批次接着做。
 * <p>
 * 抽出的字符总数为 0 时，把版本改成 {@link IngestionStatus#OCR_REQUIRED} 并结束，不再重试，也不走通用失败分类。
 * 只有空白、或切块后一条都不剩时，抛出带 {@code No searchable text chunks} 的异常，分类为不可重试的
 * {@code INVALID_DOCUMENT}。切块因超过最大片数失败时，消息对不上这组关键字，按普通依赖失败处理：
 * 尝试次数不到 3 次则可重试，否则 {@code RETRY_EXHAUSTED}。
 * 本类不判断重复入库。引用字段从任务拷进切块上下文，缺失时照样切块，不单独报「缺少引用」。
 */
public final class KnowledgeIngestionProcessor {
    private final DocumentMetadataRepository metadata;
    private final KnowledgeJobRepository jobs;
    private final KnowledgeCheckpointStore checkpoints;
    private final RawDocumentStore rawStore;
    private final DocumentParser parser;
    private final ChineseDocumentChunker chunker;
    private final DocumentEmbeddingPort embeddings;
    private final DocumentSearchIndex index;
    private final Clock clock;
    private final String parserVersion;
    private final String chunkingVersion;
    private final int embeddingBatchSize;

    /**
     * @param embeddingBatchSize 小于 1 时按 1 处理，避免批次除零
     */
    public KnowledgeIngestionProcessor(
            DocumentMetadataRepository metadata,
            KnowledgeJobRepository jobs,
            KnowledgeCheckpointStore checkpoints,
            RawDocumentStore rawStore,
            DocumentParser parser,
            ChineseDocumentChunker chunker,
            DocumentEmbeddingPort embeddings,
            DocumentSearchIndex index,
            Clock clock,
            String parserVersion,
            String chunkingVersion,
            int embeddingBatchSize) {
        this.metadata = metadata;
        this.jobs = jobs;
        this.checkpoints = checkpoints;
        this.rawStore = rawStore;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.index = index;
        this.clock = clock;
        this.parserVersion = parserVersion;
        this.chunkingVersion = chunkingVersion;
        this.embeddingBatchSize = Math.max(1, embeddingBatchSize);
    }

    /**
     * 从上次完成的步骤继续，直到就绪或失败。
     * 运行时异常交给 {@link #classify}；{@code OCR_REQUIRED} 在切块前返回，不进入该分类。
     * 状态迁移不调用 {@link IngestionStatus#canTransitionTo}，只把期望状态交给仓库做条件更新。
     */
    public void process(KnowledgeJobWorkItem item) {
        IngestionStatus current = resumeStatus(item.lastCompletedStep());
        try {
            ParsedDocument parsed = null;
            List<DocumentChunk> chunks = null;
            List<float[]> vectors = new ArrayList<>();
            if (current == IngestionStatus.REGISTERED) {
                rawStore.read(item.storageKey());
                current = move(item.versionId(), current, IngestionStatus.FETCHED);
                jobs.checkpoint(item.jobId(), "FETCHED", 0, 0, clock.instant());
            }
            if (current == IngestionStatus.FETCHED) {
                current = move(item.versionId(), current, IngestionStatus.PARSING);
                parsed = parser.parse(item.contentType(), item.originalFileName(), rawStore.read(item.storageKey()));
                if (parsed.textCharacters() == 0) {
                    metadata.transition(item.versionId(), current, IngestionStatus.OCR_REQUIRED, clock.instant());
                    jobs.releaseFailure(
                            item.jobId(),
                            IngestionStatus.OCR_REQUIRED,
                            "OCR_REQUIRED",
                            "Document has no extractable text",
                            null,
                            clock.instant());
                    return;
                }
                checkpoints.saveParsed(item.versionId(), parsed);
                metadata.updateParsed(
                        item.versionId(),
                        parsed.pages().size(),
                        parsed.textCharacters(),
                        parserVersion,
                        parsed.warnings(),
                        clock.instant());
                current = move(item.versionId(), current, IngestionStatus.PARSED);
                jobs.checkpoint(item.jobId(), "PARSED", 0, 0, clock.instant());
            }
            if (current == IngestionStatus.PARSED) {
                if (parsed == null) {
                    parsed = checkpoints.loadParsed(item.versionId());
                }
                current = move(item.versionId(), current, IngestionStatus.CHUNKING);
                ChunkingContext context = new ChunkingContext(
                        item.documentId(),
                        item.versionId(),
                        item.title(),
                        item.documentType(),
                        item.publishedDate(),
                        item.fundCodes(),
                        item.sourceName(),
                        item.sourceUri(),
                        chunkingVersion);
                chunks = chunker.split(parsed, context);
                if (chunks.isEmpty()) {
                    throw new IllegalArgumentException("No searchable text chunks were produced");
                }
                checkpoints.saveChunks(item.versionId(), chunks);
                metadata.replaceChunkMetadata(
                        item.versionId(),
                        chunks,
                        "artifacts/" + item.versionId() + "/chunks.json",
                        clock.instant());
                current = move(item.versionId(), current, IngestionStatus.CHUNKED);
                jobs.checkpoint(item.jobId(), "CHUNKED", 0, 0, clock.instant());
            }
            if (current == IngestionStatus.CHUNKED || current == IngestionStatus.EMBEDDING) {
                if (chunks == null) {
                    chunks = checkpoints.loadChunks(item.versionId());
                }
                int completed = current == IngestionStatus.EMBEDDING ? item.embeddedBatchNo() : 0;
                if (completed > 0) {
                    vectors.addAll(checkpoints.loadEmbeddingBatches(item.versionId(), completed));
                }
                if (current == IngestionStatus.CHUNKED) {
                    current = move(item.versionId(), current, IngestionStatus.EMBEDDING);
                }
                int start = completed * embeddingBatchSize;
                for (int from = start; from < chunks.size(); from += embeddingBatchSize) {
                    int to = Math.min(chunks.size(), from + embeddingBatchSize);
                    List<float[]> batch = embeddings.embed(
                            chunks.subList(from, to).stream().map(DocumentChunk::content).toList());
                    if (batch.size() != to - from) {
                        throw new IllegalStateException("EMBEDDING_COUNT_MISMATCH");
                    }
                    int batchNo = from / embeddingBatchSize + 1;
                    checkpoints.saveEmbeddingBatch(item.versionId(), batchNo, batch);
                    vectors.addAll(batch);
                    jobs.checkpoint(item.jobId(), "EMBEDDING", batchNo, 0, clock.instant());
                }
                current = move(item.versionId(), current, IngestionStatus.INDEXING);
            }
            if (current == IngestionStatus.INDEXING) {
                if (chunks == null) {
                    chunks = checkpoints.loadChunks(item.versionId());
                }
                if (vectors.isEmpty()) {
                    vectors.addAll(checkpoints.loadEmbeddingBatches(
                            item.versionId(), Math.max(item.embeddedBatchNo(), batches(chunks.size()))));
                }
                if (vectors.size() != chunks.size()) {
                    throw new IllegalStateException("EMBEDDING_COUNT_MISMATCH");
                }
                List<IndexedChunk> values = new ArrayList<>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    values.add(new IndexedChunk(chunks.get(i), vectors.get(i), embeddings.version()));
                }
                index.index(values);
                jobs.checkpoint(item.jobId(), "INDEXING", batches(chunks.size()), values.size(), clock.instant());
                index.activate(item.documentId(), item.versionId());
                metadata.updateIndexed(
                        item.versionId(),
                        chunks.size(),
                        chunkingVersion,
                        embeddings.version(),
                        index.indexVersion(),
                        clock.instant());
                current = move(item.versionId(), current, IngestionStatus.READY);
                jobs.checkpoint(item.jobId(), "READY", batches(chunks.size()), chunks.size(), clock.instant());
                jobs.releaseSuccess(item.jobId(), clock.instant());
            }
        } catch (RuntimeException error) {
            Failure failure = classify(error, item.attemptCount());
            metadata.fail(item.versionId(), item.jobId(), failure.status(), failure.code(), safe(error), clock.instant());
            jobs.releaseFailure(
                    item.jobId(),
                    failure.status(),
                    failure.code(),
                    safe(error),
                    failure.nextRetryAt(),
                    clock.instant());
        }
    }

    /**
     * 向上取整的向量批次数。
     */
    private int batches(int chunks) {
        return (chunks + embeddingBatchSize - 1) / embeddingBatchSize;
    }

    /**
     * 要求仓库把版本从 {@code from} 改到 {@code to}，并返回目标状态供后续步骤使用。
     */
    private IngestionStatus move(String id, IngestionStatus from, IngestionStatus to) {
        metadata.transition(id, from, to, clock.instant());
        return to;
    }

    /**
     * 空白或无法识别的步骤名都回到 {@link IngestionStatus#REGISTERED}，随后的步骤会再执行一遍。
     */
    private IngestionStatus resumeStatus(String value) {
        if (value == null || value.isBlank()) {
            return IngestionStatus.REGISTERED;
        }
        try {
            return IngestionStatus.valueOf(value);
        } catch (Exception ex) {
            return IngestionStatus.REGISTERED;
        }
    }

    /**
     * 按异常消息里的英文片段决定终态还是重试。消息先转成大写。
     * 含 {@code UNSUPPORTED}、{@code NO SEARCHABLE}、{@code CORRUPT} 或 {@code CHECKPOINT} 时，
     * 终态为 {@code INVALID_DOCUMENT}。含 {@code DIMENSION} 时终态为 {@code EMBEDDING_DIMENSION_MISMATCH}。
     * 这两类都不再重试。其余错误在尝试次数达到 3 次时变为 {@code RETRY_EXHAUSTED}；
     * 未达到时为可重试，消息含 {@code ELASTIC} 则错误码是 {@code ELASTICSEARCH_UNAVAILABLE}，
     * 否则是 {@code KNOWLEDGE_DEPENDENCY_UNAVAILABLE}。退避秒数是 {@code min(600, 10 << min(5, attempts))}。
     * 向量条数不一致的消息是 {@code EMBEDDING_COUNT_MISMATCH}，对不上上述关键字，因此走重试而不是维度终态。
     * 超过最大切片数的异常同样走重试。
     */
    private Failure classify(RuntimeException error, int attempts) {
        String text = (error.getMessage() == null ? "" : error.getMessage()).toUpperCase(Locale.ROOT);
        if (text.contains("UNSUPPORTED")
                || text.contains("NO SEARCHABLE")
                || text.contains("CORRUPT")
                || text.contains("CHECKPOINT")) {
            return new Failure(IngestionStatus.FAILED_FINAL, "INVALID_DOCUMENT", null);
        }
        if (text.contains("DIMENSION")) {
            return new Failure(IngestionStatus.FAILED_FINAL, "EMBEDDING_DIMENSION_MISMATCH", null);
        }
        if (attempts >= 3) {
            return new Failure(IngestionStatus.FAILED_FINAL, "RETRY_EXHAUSTED", null);
        }
        long delay = Math.min(600, 10L << Math.min(5, attempts));
        return new Failure(
                IngestionStatus.FAILED_RETRYABLE,
                text.contains("ELASTIC") ? "ELASTICSEARCH_UNAVAILABLE" : "KNOWLEDGE_DEPENDENCY_UNAVAILABLE",
                clock.instant().plusSeconds(delay));
    }

    /**
     * 没有消息时用异常类的简单名。结果最长 500 个 UTF-16 字符。
     */
    private String safe(Throwable error) {
        String value = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return value.substring(0, Math.min(500, value.length()));
    }

    /**
     * 一次失败的落库决定。终态失败的下次重试时间为 null。
     */
    private record Failure(IngestionStatus status, String code, Instant nextRetryAt) {}
}
