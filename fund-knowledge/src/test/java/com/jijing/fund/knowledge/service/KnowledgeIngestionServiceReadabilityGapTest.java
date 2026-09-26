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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 锁定注册边界对空正文、超长正文、缺失幂等键和重复入库标志的现有行为。
 */
class KnowledgeIngestionServiceReadabilityGapTest {
    private final List<String> events = new ArrayList<>();
    private final RecordingStore store = new RecordingStore();
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);

    /**
     * 空数组和 null 正文都在写存储之前被拒绝。
     */
    @Test
    void emptyDocumentIsRejectedBeforeStorage() {
        assertThatThrownBy(() -> service(false).ingest(command(new byte[0], "ext-1", uri(), "text/plain", Set.of("000001"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 byte and 50 MB");
        assertThatThrownBy(() -> service(false).ingest(command(null, "ext-1", uri(), "text/plain", Set.of("000001"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 byte and 50 MB");
        assertThat(events).isEmpty();
    }

    /**
     * 超过 50MB 的正文被拒绝，存储和仓库都不会被调用。
     */
    @Test
    void overlongBodyIsRejectedBeforeStorage() {
        byte[] body = new byte[(int) (50L * 1024 * 1024 + 1)];
        body[0] = 1;
        assertThatThrownBy(() -> service(false).ingest(command(body, "ext-1", uri(), "application/pdf", Set.of("000001"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50 MB");
        assertThat(events).isEmpty();
    }

    /**
     * 仓库返回的重复标志原样交回，但原始字节仍然先写入存储。
     */
    @Test
    void duplicateFlagIsReturnedAfterTheRawBytesAreStored() {
        DocumentRegistrationResult result = service(true).ingest(command(
                "同一份季报".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "ext-1",
                uri(),
                "text/plain",
                Set.of("000001")));
        assertThat(result.duplicate()).isTrue();
        assertThat(result.documentId()).isEqualTo("document-1");
        assertThat(events).containsExactly("store", "register");
    }

    /**
     * 外部文档号和来源 URI 都缺失时无法做版本幂等，注册不会开始。
     */
    @Test
    void missingIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> service(false).ingest(command("正文".getBytes(), "  ", null, "text/plain", Set.of("000001"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version idempotency");
        assertThat(events).isEmpty();
    }

    /**
     * 标题、基金代码或内容类型不合法时同样不会写入存储。
     */
    @Test
    void invalidMetadataIsRejectedBeforeStorage() {
        assertThatThrownBy(() -> service(false).ingest(command("正文".getBytes(), "ext-1", uri(), "text/plain", Set.of("000001"), " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
        assertThatThrownBy(() -> service(false).ingest(command("正文".getBytes(), "ext-1", uri(), "text/plain", Set.of("12345"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fundCode");
        assertThatThrownBy(() -> service(false).ingest(command("正文".getBytes(), "ext-1", uri(), "TEXT/PLAIN", Set.of("000001"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported contentType");
        assertThat(events).isEmpty();
    }

    /**
     * 用指定的重复标志组装注册服务。存储和仓库共用同一条事件记录。
     */
    private KnowledgeIngestionService service(boolean duplicate) {
        return new KnowledgeIngestionService(new FlagRepository(duplicate), store, clock);
    }

    /**
     * 组装一篇通过其他校验、只替换正文和幂等键的命令。
     */
    private static RegisterDocumentCommand command(
            byte[] content, String externalId, URI sourceUri, String contentType, Set<String> fundCodes) {
        return command(content, externalId, sourceUri, contentType, fundCodes, "季报");
    }

    /**
     * 组装注册命令。标题由调用方指定，用来覆盖空白标题。
     */
    private static RegisterDocumentCommand command(
            byte[] content, String externalId, URI sourceUri, String contentType, Set<String> fundCodes, String title) {
        return new RegisterDocumentCommand(
                externalId,
                title,
                FundDocumentType.QUARTERLY_REPORT,
                "基金公司",
                "来源",
                sourceUri,
                LocalDate.of(2026, 6, 30),
                fundCodes,
                "q.txt",
                contentType,
                content);
    }

    /**
     * 测试用的固定来源地址。
     */
    private static URI uri() {
        return URI.create("https://example.test/q.txt");
    }

    /**
     * 记下一次原文写入，不真正保存字节。
     */
    private final class RecordingStore implements RawDocumentStore {
        /**
         * 记录写入发生在仓库注册之前。
         */
        @Override
        public String store(String contentSha256, String originalFileName, byte[] content) {
            events.add("store");
            return contentSha256 + ".bin";
        }

        /**
         * 本测试不读原文。
         */
        @Override
        public byte[] read(String storageKey) {
            throw new AssertionError("read is not used");
        }
    }

    /**
     * 按构造时的标志返回重复或新注册，并记录注册调用。
     */
    private final class FlagRepository implements DocumentMetadataRepository {
        private final boolean duplicate;

        /**
         * @param duplicate 仓库将要返回的重复标志
         */
        private FlagRepository(boolean duplicate) {
            this.duplicate = duplicate;
        }

        /**
         * 记录注册，并按预设标志返回固定标识。
         */
        @Override
        public DocumentRegistrationResult register(
                RegisterDocumentCommand command, String contentSha256, String storageKey, Instant now) {
            events.add("register");
            return new DocumentRegistrationResult("document-1", "version-1", "job-1", IngestionStatus.REGISTERED, duplicate);
        }

        /**
         * 注册测试不迁移状态。
         */
        @Override
        public void transition(String versionId, IngestionStatus expected, IngestionStatus next, Instant at) {
            throw new AssertionError("transition is not used");
        }

        /**
         * 注册测试不写解析结果。
         */
        @Override
        public void updateParsed(
                String versionId, int pageCount, long textCharCount, String parserVersion, List<String> warnings, Instant at) {
            throw new AssertionError("updateParsed is not used");
        }

        /**
         * 注册测试不写索引结果。
         */
        @Override
        public void updateIndexed(
                String versionId, int chunkCount, String chunkingVersion, String embeddingVersion, String indexName, Instant at) {
            throw new AssertionError("updateIndexed is not used");
        }

        /**
         * 注册测试不记失败。
         */
        @Override
        public void fail(String versionId, String jobId, IngestionStatus status, String errorCode, String safeMessage, Instant at) {
            throw new AssertionError("fail is not used");
        }
    }
}
