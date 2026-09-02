package com.jijing.fund.knowledge.domain;

import java.time.LocalDate;
import java.util.Set;

public record KnowledgeJobWorkItem(String jobId, String documentId, String versionId,
        String storageKey, String originalFileName, String contentType, String title,
        FundDocumentType documentType, LocalDate publishedDate, Set<String> fundCodes,
        String sourceName, String sourceUri, int attemptCount, String lastCompletedStep, int embeddedBatchNo) {
    public KnowledgeJobWorkItem { fundCodes = fundCodes == null ? Set.of() : Set.copyOf(fundCodes); }
}
