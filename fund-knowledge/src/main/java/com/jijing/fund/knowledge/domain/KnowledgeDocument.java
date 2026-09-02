package com.jijing.fund.knowledge.domain;

import java.net.URI;
import java.time.*;
import java.util.Set;

public record KnowledgeDocument(String documentId, String externalDocumentId, String title,
        FundDocumentType documentType, String publisher, String sourceName, URI sourceUri,
        LocalDate publishedDate, Set<String> fundCodes, String activeVersionId, Instant createdAt) {
    public KnowledgeDocument {
        fundCodes = fundCodes == null ? Set.of() : Set.copyOf(fundCodes);
    }
}
