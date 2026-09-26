package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.IngestionStatus;
import java.time.Instant;
import java.util.List;

/**
 * 对外展示的一个文档版本。页数、字符数、各算法版本和就绪时间在对应步骤完成前可以为 null。
 * 警告列表由仓库填充，本模块的记录类型不在这里把 null 收成空列表。
 */
public record KnowledgeVersionView(
        String versionId,
        String documentId,
        int versionNo,
        String contentSha256,
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
        Instant readyAt) {}
