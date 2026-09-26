package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.FundDocumentType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 对外展示的文档，带版本列表和关联基金。
 * 来源 URI、发布日期和当前生效版本允许为空。本视图不判断资料是否重复，也不检查引用是否完整。
 */
public record KnowledgeDocumentView(
        String documentId,
        String externalDocumentId,
        String title,
        FundDocumentType documentType,
        String publisher,
        String sourceName,
        String sourceUri,
        LocalDate publishedDate,
        String activeVersionId,
        Set<String> fundCodes,
        List<KnowledgeVersionView> versions,
        Instant createdAt,
        Instant updatedAt) {}
