package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.IngestionStatus;
import java.time.Instant;

public record KnowledgeJobView(String jobId, String documentId, String versionId,
        IngestionStatus status, String currentStep, String lastCompletedStep,
        int attemptCount, Integer chunkCount, int embeddedBatchNo, int indexedChunkCount,
        String leaseOwner, Instant leaseUntil, Instant nextRetryAt, String errorCode,
        String safeErrorMessage, Instant startedAt, Instant updatedAt, Instant completedAt) {}
