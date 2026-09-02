package com.jijing.fund.knowledge.port;

import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.domain.*;
import java.time.Instant;
import java.util.List;

public interface DocumentMetadataRepository {
    DocumentRegistrationResult register(RegisterDocumentCommand command, String contentSha256,
            String storageKey, Instant now);
    void transition(String versionId, IngestionStatus expected, IngestionStatus next, Instant at);
    void updateParsed(String versionId, int pageCount, long textCharCount, String parserVersion,
            List<String> warnings, Instant at);
    void updateIndexed(String versionId, int chunkCount, String chunkingVersion,
            String embeddingVersion, String indexName, Instant at);
    void fail(String versionId, String jobId, IngestionStatus status, String errorCode,
            String safeMessage, Instant at);
    default void replaceChunkMetadata(String versionId,List<DocumentChunk>chunks,String artifactStorageKey,Instant at) {}
}
