package com.jijing.fund.knowledge.domain;

import java.time.LocalDate;
import java.util.Set;

/**
 * 一段可检索的正文，以及生成引用所需的页码、标题和来源。
 * 标题路径、文档标题、来源名称、来源 URI 和发布日期都允许为 null 或空串。
 * 检索、重排、融合和预算都不会因为这些引用字段缺失而丢弃切片。
 * 基金代码为 null 时收成空集。相邻切片合并时，除页码取并集外，引用字段保留左侧切片的值。
 */
public record DocumentChunk(
        String chunkId,
        String documentId,
        String versionId,
        String content,
        String headingPath,
        int pageStart,
        int pageEnd,
        int chunkOrder,
        int tokenCount,
        String chunkingVersion,
        String contentSha256,
        Set<String> fundCodes,
        FundDocumentType documentType,
        String documentTitle,
        LocalDate publishedDate,
        String sourceName,
        String sourceUri) {
    /**
     * 复制基金代码。null 视为切片不限定基金。
     */
    public DocumentChunk {
        fundCodes = fundCodes == null ? Set.of() : Set.copyOf(fundCodes);
    }
}
