package com.jijing.fund.knowledge.domain;

import java.time.Instant;
import java.util.List;

public record DocumentVersion(String versionId, String documentId, int versionNo, String contentSha256,
        String storageKey, String contentType, long fileSize, Integer pageCount, Long textCharCount,
        IngestionStatus status, String parserVersion, String chunkingVersion, String embeddingVersion,
        String indexName, List<String> warnings, Instant createdAt, Instant readyAt) {
    public DocumentVersion { warnings = warnings == null ? List.of() : List.copyOf(warnings); }
}
