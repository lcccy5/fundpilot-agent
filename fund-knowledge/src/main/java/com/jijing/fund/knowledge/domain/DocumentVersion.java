package com.jijing.fund.knowledge.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一份已注册文档字节的版本。状态、解析版本和索引名由入库流程逐步填上，注册当时可以为空。
 * 警告列表为 null 时收成空列表。
 */
public record DocumentVersion(
        String versionId,
        String documentId,
        int versionNo,
        String contentSha256,
        String storageKey,
        String contentType,
        long fileSize,
        Integer pageCount,
        Long textCharCount,
        IngestionStatus status,
        String parserVersion,
        String chunkingVersion,
        String embeddingVersion,
        String indexName,
        List<String> warnings,
        Instant createdAt,
        Instant readyAt) {
    /**
     * 复制警告列表。null 视为没有警告。
     */
    public DocumentVersion {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
