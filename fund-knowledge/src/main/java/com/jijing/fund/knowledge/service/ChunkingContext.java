package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.FundDocumentType;
import java.time.LocalDate;
import java.util.Set;

/**
 * 切块时拷进每片的文档级引用。
 * 来源名称、来源 URI、标题和发布日期允许为 null；切块器不会因为它们缺失而拒绝成片。
 * 基金代码允许为 null，真正写入切片时由 {@link com.jijing.fund.knowledge.domain.DocumentChunk} 收成空集。
 */
public record ChunkingContext(
        String documentId,
        String versionId,
        String title,
        FundDocumentType documentType,
        LocalDate publishedDate,
        Set<String> fundCodes,
        String sourceName,
        String sourceUri,
        String chunkingVersion) {}
