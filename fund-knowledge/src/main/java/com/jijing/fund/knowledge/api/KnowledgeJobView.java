package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.IngestionStatus;
import java.time.Instant;

/**
 * 对外展示的入库任务。租约、下次重试、错误码和安全错误信息在任务尚未失败或尚未被领取时可以为 null。
 * 错误信息最长以仓库保存的为准；处理流程写入前会截到 500 个字符。
 */
public record KnowledgeJobView(
        String jobId,
        String documentId,
        String versionId,
        IngestionStatus status,
        String currentStep,
        String lastCompletedStep,
        int attemptCount,
        Integer chunkCount,
        int embeddedBatchNo,
        int indexedChunkCount,
        String leaseOwner,
        Instant leaseUntil,
        Instant nextRetryAt,
        String errorCode,
        String safeErrorMessage,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt) {}
