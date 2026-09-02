package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.IngestionStatus;
import java.time.Instant;
import java.util.List;

public record KnowledgeVersionView(String versionId, String documentId, int versionNo,
        String contentSha256, String contentType, long fileSize, Integer pageCount,
        Long textCharCount, IngestionStatus status, String parserVersion,
        String chunkingVersion, String embeddingVersion, String indexName,
        List<String> warnings, Instant createdAt, Instant readyAt) {}
