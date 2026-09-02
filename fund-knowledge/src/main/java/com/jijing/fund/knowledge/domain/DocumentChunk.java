package com.jijing.fund.knowledge.domain;

import java.time.LocalDate;
import java.util.Set;

public record DocumentChunk(String chunkId, String documentId, String versionId, String content,
        String headingPath, int pageStart, int pageEnd, int chunkOrder, int tokenCount,
        String chunkingVersion, String contentSha256, Set<String> fundCodes,
        FundDocumentType documentType, String documentTitle, LocalDate publishedDate,
        String sourceName, String sourceUri) {
    public DocumentChunk { fundCodes = fundCodes == null ? Set.of() : Set.copyOf(fundCodes); }
}
