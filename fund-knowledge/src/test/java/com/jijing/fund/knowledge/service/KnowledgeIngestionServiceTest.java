package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.api.DocumentRegistrationResult;
import com.jijing.fund.knowledge.api.RegisterDocumentCommand;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.IngestionStatus;
import com.jijing.fund.knowledge.port.DocumentMetadataRepository;
import com.jijing.fund.knowledge.port.RawDocumentStore;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认注册在受理后立即返回，不在这次调用里推进解析或索引。
 */
class KnowledgeIngestionServiceTest {
    /**
     * 合法命令返回已注册状态，并且不会发生后续状态迁移。
     */
    @Test
    void registrationReturnsBeforeHeavyPipeline() {
        FakeRepository repository = new FakeRepository();
        RawDocumentStore raw = new MemoryStore();
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                repository, raw, Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));
        RegisterDocumentCommand command = new RegisterDocumentCommand(
                "ext-1",
                "季度报告",
                FundDocumentType.QUARTERLY_REPORT,
                "基金公司",
                "test",
                URI.create("https://example.test/q.pdf"),
                LocalDate.of(2026, 6, 30),
                Set.of("000001"),
                "q.txt",
                "text/plain",
                "正文".getBytes());
        DocumentRegistrationResult result = service.ingest(command);
        assertThat(result.status()).isEqualTo(IngestionStatus.REGISTERED);
        assertThat(repository.transitions).isEmpty();
    }

    /**
     * 只记录状态迁移的元数据替身。注册固定返回一条新任务。
     */
    private static final class FakeRepository implements DocumentMetadataRepository {
        private final List<IngestionStatus> transitions = new ArrayList<>();

        /**
         * 返回固定的新注册结果，不表示重复。
         */
        @Override
        public DocumentRegistrationResult register(
                RegisterDocumentCommand command, String hash, String storageKey, Instant now) {
            return new DocumentRegistrationResult("document-1", "version-1", "job-1", IngestionStatus.REGISTERED, false);
        }

        /**
         * 记下迁移后的状态。注册成功时不应发生任何迁移。
         */
        @Override
        public void transition(String versionId, IngestionStatus expected, IngestionStatus next, Instant at) {
            transitions.add(next);
        }

        /**
         * 注册测试不写解析结果。
         */
        @Override
        public void updateParsed(
                String versionId, int pageCount, long textCharCount, String parserVersion, List<String> warnings, Instant at) {}

        /**
         * 注册测试不写索引结果。
         */
        @Override
        public void updateIndexed(
                String versionId, int chunkCount, String chunkingVersion, String embeddingVersion, String indexName, Instant at) {}

        /**
         * 这条成功路径不应记失败。
         */
        @Override
        public void fail(String versionId, String jobId, IngestionStatus status, String errorCode, String safeMessage, Instant at) {
            throw new AssertionError("unexpected failure");
        }
    }

    /**
     * 把原文键记成哈希加后缀，不保存字节。
     */
    private static final class MemoryStore implements RawDocumentStore {
        /**
         * 返回一个可由哈希推导的存储键。
         */
        @Override
        public String store(String hash, String name, byte[] bytes) {
            return hash + ".txt";
        }

        /**
         * 注册测试不读回原文。
         */
        @Override
        public byte[] read(String key) {
            return new byte[0];
        }
    }
}
