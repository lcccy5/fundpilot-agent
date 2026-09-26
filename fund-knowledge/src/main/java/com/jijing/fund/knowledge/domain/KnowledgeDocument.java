package com.jijing.fund.knowledge.domain;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/**
 * 一份知识库文档的稳定身份。外部文档号或来源 URI 供仓库做版本幂等，本记录不比较重复。
 * 来源 URI、发布日期和当前生效版本允许为 null。基金代码为 null 时收成空集。
 */
public record KnowledgeDocument(
        String documentId,
        String externalDocumentId,
        String title,
        FundDocumentType documentType,
        String publisher,
        String sourceName,
        URI sourceUri,
        LocalDate publishedDate,
        Set<String> fundCodes,
        String activeVersionId,
        Instant createdAt) {
    /**
     * 复制基金代码。null 视为未关联任何基金。
     */
    public KnowledgeDocument {
        fundCodes = fundCodes == null ? Set.of() : Set.copyOf(fundCodes);
    }
}
