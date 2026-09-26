package com.jijing.fund.knowledge.port;

import com.jijing.fund.knowledge.api.DocumentRegistrationResult;
import com.jijing.fund.knowledge.api.RegisterDocumentCommand;
import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.IngestionStatus;
import java.time.Instant;
import java.util.List;

/**
 * 文档和版本元数据。重复入库在 {@link #register} 里判定：同一来源下同一内容哈希应返回
 * {@code duplicate=true} 以及已有标识。注册服务在调用本方法前已经写过原始字节，
 * 因此重复判定不能阻止那一次原文写入。
 * 状态迁移应校验期望状态，避免并发工人把版本写飞。
 */
public interface DocumentMetadataRepository {
    /**
     * 登记版本和入库任务。重复时返回已有记录且重复标志为 true，不要再插一条新版本。
     */
    DocumentRegistrationResult register(
            RegisterDocumentCommand command,
            String contentSha256,
            String storageKey,
            Instant now);

    /**
     * 仅当版本当前状态等于 {@code expected} 时改成 {@code next}。
     */
    void transition(String versionId, IngestionStatus expected, IngestionStatus next, Instant at);

    /**
     * 写入页数、字符数、解析器版本和解析警告。
     */
    void updateParsed(
            String versionId,
            int pageCount,
            long textCharCount,
            String parserVersion,
            List<String> warnings,
            Instant at);

    /**
     * 写入切片数、切块版本、向量版本和索引名。
     */
    void updateIndexed(
            String versionId,
            int chunkCount,
            String chunkingVersion,
            String embeddingVersion,
            String indexName,
            Instant at);

    /**
     * 把版本和任务记成失败。安全错误信息由调用方截断。
     */
    void fail(String versionId, String jobId, IngestionStatus status, String errorCode, String safeMessage, Instant at);

    /**
     * 用最新切片替换该版本的切片元数据。默认空实现，方便尚不保存切片行的测试替身。
     */
    default void replaceChunkMetadata(
            String versionId,
            List<DocumentChunk> chunks,
            String artifactStorageKey,
            Instant at) {}
}
