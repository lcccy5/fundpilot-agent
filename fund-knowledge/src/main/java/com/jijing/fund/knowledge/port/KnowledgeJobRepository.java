package com.jijing.fund.knowledge.port;

import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.domain.*;
import java.time.*;
import java.util.Optional;

public interface KnowledgeJobRepository {
    Optional<KnowledgeJobWorkItem> claim(String workerId, Instant now, Duration leaseDuration);
    boolean heartbeat(String jobId, String workerId, Instant now, Duration leaseDuration);
    void checkpoint(String jobId, String step, int embeddedBatchNo, int indexedChunkCount, Instant now);
    void releaseSuccess(String jobId, Instant now);
    void releaseFailure(String jobId, IngestionStatus status, String errorCode, String safeMessage,
            Instant nextRetryAt, Instant now);
    KnowledgeJobView findJob(String jobId);
    KnowledgeDocumentView findDocument(String documentId);
    KnowledgeVersionView findVersion(String versionId);
    KnowledgeJobView retry(String jobId, Instant now);
}
