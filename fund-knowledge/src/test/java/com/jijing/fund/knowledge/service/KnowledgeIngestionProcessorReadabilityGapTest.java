package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.api.DocumentRegistrationResult;
import com.jijing.fund.knowledge.api.KnowledgeDocumentView;
import com.jijing.fund.knowledge.api.KnowledgeJobView;
import com.jijing.fund.knowledge.api.KnowledgeVersionView;
import com.jijing.fund.knowledge.api.RegisterDocumentCommand;
import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.IndexedChunk;
import com.jijing.fund.knowledge.domain.IngestionStatus;
import com.jijing.fund.knowledge.domain.KnowledgeJobWorkItem;
import com.jijing.fund.knowledge.domain.ParsedDocument;
import com.jijing.fund.knowledge.domain.ParsedPage;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import com.jijing.fund.knowledge.api.KnowledgeSearchQuery;
import com.jijing.fund.knowledge.port.DocumentEmbeddingPort;
import com.jijing.fund.knowledge.port.DocumentMetadataRepository;
import com.jijing.fund.knowledge.port.DocumentParser;
import com.jijing.fund.knowledge.port.DocumentSearchIndex;
import com.jijing.fund.knowledge.port.KnowledgeCheckpointStore;
import com.jijing.fund.knowledge.port.KnowledgeJobRepository;
import com.jijing.fund.knowledge.port.RawDocumentStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定入库处理在空文档、切不出文本、超长切块和缺失引用上的现有失败分类。
 */
class KnowledgeIngestionProcessorReadabilityGapTest {
    private final Instant now = Instant.parse("2026-08-23T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final List<String> transitions = new ArrayList<>();
    private final List<IndexedChunk> indexed = new ArrayList<>();
    private ParsedDocument parsed = new ParsedDocument(List.of(), List.of());
    private RuntimeException parseError;
    private RuntimeException checkpointError;
    private List<float[]> vectors = List.of(new float[] {1.0f});
    private boolean failCalled;
    private IngestionStatus failedStatus;
    private String failedCode;
    private String failedMessage;
    private Instant failedRetryAt;
    private boolean success;
    private String activatedVersion;

    /**
     * 抽不出任何字符时停在需要 OCR，不进入通用失败分类，也不安排重试。
     */
    @Test
    void emptyExtractableTextStopsAtOcrWithoutRetry() {
        parsed = new ParsedDocument(List.of(new ParsedPage(1, null, "")), List.of());
        processor(new ChineseDocumentChunker(20, 40, 0, 5, 10)).process(work(null, 0));
        assertThat(failCalled).isFalse();
        assertThat(failedStatus).isEqualTo(IngestionStatus.OCR_REQUIRED);
        assertThat(failedCode).isEqualTo("OCR_REQUIRED");
        assertThat(failedMessage).isEqualTo("Document has no extractable text");
        assertThat(failedRetryAt).isNull();
        assertThat(transitions).contains("PARSING->OCR_REQUIRED");
        assertThat(success).isFalse();
    }

    /**
     * 只有空白时字符总数不为 0，切块结果为空，最终判定为无效文档而不是 OCR。
     */
    @Test
    void whitespaceOnlyTextBecomesFinalInvalidDocument() {
        parsed = new ParsedDocument(List.of(new ParsedPage(1, "标题", " \n\t")), List.of());
        processor(new ChineseDocumentChunker(40, 80, 0, 30, 10)).process(work("FETCHED", 0));
        assertThat(failCalled).isTrue();
        assertThat(failedStatus).isEqualTo(IngestionStatus.FAILED_FINAL);
        assertThat(failedCode).isEqualTo("INVALID_DOCUMENT");
        assertThat(failedMessage).contains("No searchable text chunks");
        assertThat(failedRetryAt).isNull();
        assertThat(transitions).doesNotContain("PARSING->OCR_REQUIRED");
    }

    /**
     * 中文长到超过最大片数时按可重试依赖失败处理，第一次退避 10 秒。
     */
    @Test
    void exceedingTheChunkLimitIsRetryable() {
        parsed = new ParsedDocument(List.of(new ParsedPage(1, "标题", "一二三四五六七八九。甲乙丙丁戊己庚辛壬。")), List.of());
        processor(new ChineseDocumentChunker(5, 20, 0, 1, 1)).process(work(null, 0));
        assertThat(failedStatus).isEqualTo(IngestionStatus.FAILED_RETRYABLE);
        assertThat(failedCode).isEqualTo("KNOWLEDGE_DEPENDENCY_UNAVAILABLE");
        assertThat(failedMessage).contains("maximum chunk count");
        assertThat(failedRetryAt).isEqualTo(now.plusSeconds(10));
    }

    /**
     * 同一种超限在第 3 次尝试时不再重试。
     */
    @Test
    void exceedingTheChunkLimitBecomesFinalAfterThreeAttempts() {
        parsed = new ParsedDocument(List.of(new ParsedPage(1, "标题", "一二三四五六七八九。甲乙丙丁戊己庚辛壬。")), List.of());
        processor(new ChineseDocumentChunker(5, 20, 0, 1, 1)).process(work(null, 3));
        assertThat(failedStatus).isEqualTo(IngestionStatus.FAILED_FINAL);
        assertThat(failedCode).isEqualTo("RETRY_EXHAUSTED");
        assertThat(failedRetryAt).isNull();
    }

    /**
     * 向量条数对不上时走重试，不会因为消息里没有 DIMENSION 而被当成维度终态。
     */
    @Test
    void embeddingCountMismatchIsRetryable() {
        parsed = new ParsedDocument(List.of(new ParsedPage(1, "标题", "一二三四五六七八九。")), List.of());
        vectors = List.of();
        processor(new ChineseDocumentChunker(5, 20, 0, 1, 10)).process(work(null, 1));
        assertThat(failedStatus).isEqualTo(IngestionStatus.FAILED_RETRYABLE);
        assertThat(failedCode).isEqualTo("KNOWLEDGE_DEPENDENCY_UNAVAILABLE");
        assertThat(failedMessage).isEqualTo("EMBEDDING_COUNT_MISMATCH");
        assertThat(failedRetryAt).isEqualTo(now.plusSeconds(20));
    }

    /**
     * 检查点缺失和维度不匹配都是终态，与尝试次数无关。
     */
    @Test
    void checkpointAndDimensionFailuresAreFinal() {
        checkpointError = new IllegalStateException("missing CHECKPOINT");
        processor(new ChineseDocumentChunker(5, 20, 0, 1, 10)).process(work("PARSED", 0));
        assertThat(failedStatus).isEqualTo(IngestionStatus.FAILED_FINAL);
        assertThat(failedCode).isEqualTo("INVALID_DOCUMENT");
        assertThat(failedRetryAt).isNull();

        failCalled = false;
        parseError = new IllegalStateException("bad DIMENSION");
        processor(new ChineseDocumentChunker(5, 20, 0, 1, 10)).process(work(null, 0));
        assertThat(failedCode).isEqualTo("EMBEDDING_DIMENSION_MISMATCH");
        assertThat(failedStatus).isEqualTo(IngestionStatus.FAILED_FINAL);
    }

    /**
     * 对外保存的失败消息最多 500 个字符。没有消息时使用异常类名。
     */
    @Test
    void failureMessageIsTruncatedAndNullMessageUsesTheClassName() {
        parseError = new RuntimeException("m".repeat(600));
        processor(new ChineseDocumentChunker(5, 20, 0, 1, 10)).process(work(null, 0));
        assertThat(failedMessage).hasSize(500);
        assertThat(failedCode).isEqualTo("KNOWLEDGE_DEPENDENCY_UNAVAILABLE");

        parseError = new RuntimeException((String) null);
        processor(new ChineseDocumentChunker(5, 20, 0, 1, 10)).process(work(null, 0));
        assertThat(failedMessage).isEqualTo("RuntimeException");
    }

    /**
     * 无法识别的步骤名会从注册重新开始；若正文为空，仍然停在 OCR。
     */
    @Test
    void unknownCheckpointRestartsAndStillDetectsAnEmptyDocument() {
        parsed = new ParsedDocument(List.of(), List.of());
        processor(new ChineseDocumentChunker(5, 20, 0, 1, 10)).process(work("not-a-status", 0));
        assertThat(transitions).startsWith("REGISTERED->FETCHED");
        assertThat(failedStatus).isEqualTo(IngestionStatus.OCR_REQUIRED);
    }

    /**
     * 来源和标题都缺失时，切片仍会写入索引并激活版本。
     */
    @Test
    void missingCitationDoesNotBlockIndexing() {
        parsed = new ParsedDocument(List.of(new ParsedPage(2, null, "一二三四五六七八九。")), List.of());
        processor(new ChineseDocumentChunker(5, 20, 0, 1, 10)).process(work(null, 0, null, null, null));
        assertThat(success).isTrue();
        assertThat(failCalled).isFalse();
        assertThat(activatedVersion).isEqualTo("ver-1");
        assertThat(indexed).hasSize(1);
        DocumentChunk chunk = indexed.getFirst().chunk();
        assertThat(chunk.headingPath()).isEmpty();
        assertThat(chunk.documentTitle()).isNull();
        assertThat(chunk.sourceName()).isNull();
        assertThat(chunk.sourceUri()).isNull();
        assertThat(chunk.pageStart()).isEqualTo(2);
    }

    /**
     * 用指定切块器组装处理器。批次大小为 2，小于 1 的配置不在这些失败用例里覆盖。
     */
    private KnowledgeIngestionProcessor processor(ChineseDocumentChunker chunker) {
        return new KnowledgeIngestionProcessor(
                new RecordingMetadata(),
                new RecordingJobs(),
                new RecordingCheckpoints(),
                new MemoryStore(),
                new ScriptedParser(),
                chunker,
                new ScriptedEmbeddings(),
                new RecordingIndex(),
                clock,
                "parser-v1",
                "chunk-v1",
                2);
    }

    /**
     * 组装一条来源为空的任务，标题仍使用季报。
     */
    private KnowledgeJobWorkItem work(String step, int attempts) {
        return work(step, attempts, "季报", null, null);
    }

    /**
     * 组装任务。标题和来源可以由用例故意留空。
     */
    private KnowledgeJobWorkItem work(String step, int attempts, String title, String sourceName, String sourceUri) {
        return new KnowledgeJobWorkItem(
                "job-1",
                "doc-1",
                "ver-1",
                "storage-1",
                "a.txt",
                "text/plain",
                title,
                FundDocumentType.QUARTERLY_REPORT,
                LocalDate.of(2026, 6, 30),
                Set.of("000001"),
                sourceName,
                sourceUri,
                attempts,
                step,
                0);
    }

    /**
     * 记下状态迁移和失败分类，不持久化。
     */
    private final class RecordingMetadata implements DocumentMetadataRepository {
        /**
         * 处理测试不从注册入口进入。
         */
        @Override
        public DocumentRegistrationResult register(
                RegisterDocumentCommand command, String contentSha256, String storageKey, Instant at) {
            throw new AssertionError("register is not used");
        }

        /**
         * 按「原状态到新状态」记下迁移。
         */
        @Override
        public void transition(String versionId, IngestionStatus expected, IngestionStatus next, Instant at) {
            transitions.add(expected + "->" + next);
        }

        /**
         * 解析结果在这些断言里不需要回读。
         */
        @Override
        public void updateParsed(
                String versionId, int pageCount, long textCharCount, String parserVersion, List<String> warnings, Instant at) {}

        /**
         * 索引版本在这些断言里不需要回读。
         */
        @Override
        public void updateIndexed(
                String versionId, int chunkCount, String chunkingVersion, String embeddingVersion, String indexName, Instant at) {}

        /**
         * 记下通用失败分类的状态、错误码和对外消息。
         */
        @Override
        public void fail(String versionId, String jobId, IngestionStatus status, String errorCode, String safeMessage, Instant at) {
            failCalled = true;
            failedStatus = status;
            failedCode = errorCode;
            failedMessage = safeMessage;
        }
    }

    /**
     * 记下任务成功或失败释放。领取和查询不在处理用例里使用。
     */
    private final class RecordingJobs implements KnowledgeJobRepository {
        /**
         * 处理测试不领取任务。
         */
        @Override
        public Optional<KnowledgeJobWorkItem> claim(String workerId, Instant at, Duration leaseDuration) {
            throw new AssertionError("claim is not used");
        }

        /**
         * 处理测试不续租。
         */
        @Override
        public boolean heartbeat(String jobId, String workerId, Instant at, Duration leaseDuration) {
            throw new AssertionError("heartbeat is not used");
        }

        /**
         * 检查点内容不参与这些失败断言。
         */
        @Override
        public void checkpoint(String jobId, String step, int embeddedBatchNo, int indexedChunkCount, Instant at) {}

        /**
         * 记下任务已经成功结束。
         */
        @Override
        public void releaseSuccess(String jobId, Instant at) {
            success = true;
        }

        /**
         * 记下失败释放。需要 OCR 时不会调用元数据的 fail，所以这里单独保存分类。
         */
        @Override
        public void releaseFailure(
                String jobId, IngestionStatus status, String errorCode, String safeMessage, Instant nextRetryAt, Instant at) {
            failedStatus = status;
            failedCode = errorCode;
            failedMessage = safeMessage;
            failedRetryAt = nextRetryAt;
        }

        /**
         * 处理测试不查询任务视图。
         */
        @Override
        public KnowledgeJobView findJob(String jobId) {
            throw new AssertionError("findJob is not used");
        }

        /**
         * 处理测试不查询文档视图。
         */
        @Override
        public KnowledgeDocumentView findDocument(String documentId) {
            throw new AssertionError("findDocument is not used");
        }

        /**
         * 处理测试不查询版本视图。
         */
        @Override
        public KnowledgeVersionView findVersion(String versionId) {
            throw new AssertionError("findVersion is not used");
        }

        /**
         * 处理测试不重试任务。
         */
        @Override
        public KnowledgeJobView retry(String jobId, Instant at) {
            throw new AssertionError("retry is not used");
        }
    }

    /**
     * 检查点默认不可读。用例可以把读取设成失败，用来覆盖缺失检查点。
     */
    private final class RecordingCheckpoints implements KnowledgeCheckpointStore {
        /**
         * 保存解析结果在这些用例里是空操作。
         */
        @Override
        public void saveParsed(String versionId, ParsedDocument document) {}

        /**
         * 预设的检查点错误优先抛出，否则说明用例走错了恢复分支。
         */
        @Override
        public ParsedDocument loadParsed(String versionId) {
            if (checkpointError != null) {
                throw checkpointError;
            }
            throw new AssertionError("parsed checkpoint was not expected");
        }

        /**
         * 保存切片在这些用例里是空操作。
         */
        @Override
        public void saveChunks(String versionId, List<DocumentChunk> chunks) {}

        /**
         * 这些失败用例不从切片检查点恢复。
         */
        @Override
        public List<DocumentChunk> loadChunks(String versionId) {
            throw new AssertionError("chunk checkpoint was not expected");
        }

        /**
         * 保存向量批次在这些用例里是空操作。
         */
        @Override
        public void saveEmbeddingBatch(String versionId, int batchNo, List<float[]> batch) {}

        /**
         * 这些失败用例不从向量检查点恢复。
         */
        @Override
        public List<float[]> loadEmbeddingBatches(String versionId, int completedBatchCount) {
            throw new AssertionError("embedding checkpoint was not expected");
        }
    }

    /**
     * 读回一个非空字节，避免存储本身造成失败。
     */
    private static final class MemoryStore implements RawDocumentStore {
        /**
         * 处理测试不写原文。
         */
        @Override
        public String store(String contentSha256, String originalFileName, byte[] content) {
            throw new AssertionError("store is not used");
        }

        /**
         * 返回一个字节，表示对象存在且非空文件。
         */
        @Override
        public byte[] read(String storageKey) {
            return new byte[] {1};
        }
    }

    /**
     * 按用例预设返回解析结果或抛出解析错误。
     */
    private final class ScriptedParser implements DocumentParser {
        /**
         * 预设错误优先抛出，否则返回当前解析结果。
         */
        @Override
        public ParsedDocument parse(String contentType, String fileName, byte[] content) {
            if (parseError != null) {
                throw parseError;
            }
            return parsed;
        }
    }

    /**
     * 返回用例预设的向量条数，用来制造条数不一致。
     */
    private final class ScriptedEmbeddings implements DocumentEmbeddingPort {
        /**
         * 返回固定向量版本。
         */
        @Override
        public String version() {
            return "emb-v1";
        }

        /**
         * 不看正文，直接返回预设向量。
         */
        @Override
        public List<float[]> embed(List<String> texts) {
            return vectors;
        }

        /**
         * 入库处理不对查询算向量。
         */
        @Override
        public float[] embedQuery(String text) {
            throw new AssertionError("embedQuery is not used");
        }
    }

    /**
     * 收下索引写入并记下被激活的版本。
     */
    private final class RecordingIndex implements DocumentSearchIndex {
        /**
         * 返回固定索引版本。
         */
        @Override
        public String indexVersion() {
            return "index-v1";
        }

        /**
         * 保存即将写入的切片，供缺失引用断言读取。
         */
        @Override
        public void index(List<IndexedChunk> chunks) {
            indexed.addAll(chunks);
        }

        /**
         * 记下被激活的版本。
         */
        @Override
        public void activate(String documentId, String versionId) {
            activatedVersion = versionId;
        }

        /**
         * 入库处理不做词法检索。
         */
        @Override
        public List<RetrievedChunk> lexicalSearch(KnowledgeSearchQuery query, int topK) {
            throw new AssertionError("lexicalSearch is not used");
        }

        /**
         * 入库处理不做向量检索。
         */
        @Override
        public List<RetrievedChunk> vectorSearch(KnowledgeSearchQuery query, float[] queryEmbedding, int topK) {
            throw new AssertionError("vectorSearch is not used");
        }
    }
}
