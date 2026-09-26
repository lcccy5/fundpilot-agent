package com.jijing.fund.knowledge.port;

import com.jijing.fund.knowledge.api.KnowledgeDocumentView;
import com.jijing.fund.knowledge.api.KnowledgeJobView;
import com.jijing.fund.knowledge.api.KnowledgeVersionView;
import com.jijing.fund.knowledge.domain.IngestionStatus;
import com.jijing.fund.knowledge.domain.KnowledgeJobWorkItem;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 入库任务的领取、心跳、检查点和查询。
 * 文档、版本或任务不存在时由实现抛出找不到的异常。重试不被允许时由实现抛出冲突异常。
 * 本模块的管理服务不翻译这些异常。
 */
public interface KnowledgeJobRepository {
    /**
     * 领取一条到期任务。没有可领取任务时返回空，这不是错误。
     */
    Optional<KnowledgeJobWorkItem> claim(String workerId, Instant now, Duration leaseDuration);

    /**
     * 续租。任务已不属于该工人时返回 false。
     */
    boolean heartbeat(String jobId, String workerId, Instant now, Duration leaseDuration);

    /**
     * 记下当前步骤、已完成的向量批次和已写入索引的切片数。
     */
    void checkpoint(String jobId, String step, int embeddedBatchNo, int indexedChunkCount, Instant now);

    /**
     * 任务成功结束后释放租约。
     */
    void releaseSuccess(String jobId, Instant now);

    /**
     * 任务失败后释放租约。{@code nextRetryAt} 为 null 表示不再自动重试，例如需要 OCR 或最终失败。
     */
    void releaseFailure(
            String jobId,
            IngestionStatus status,
            String errorCode,
            String safeMessage,
            Instant nextRetryAt,
            Instant now);

    /**
     * 按任务标识读取视图。
     */
    KnowledgeJobView findJob(String jobId);

    /**
     * 按文档标识读取视图。
     */
    KnowledgeDocumentView findDocument(String documentId);

    /**
     * 按版本标识读取视图。
     */
    KnowledgeVersionView findVersion(String versionId);

    /**
     * 重试一条失败任务。调用方传入当前时间。
     */
    KnowledgeJobView retry(String jobId, Instant now);
}
