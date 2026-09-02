package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.FundDocumentType;
import java.time.*;
import java.util.*;

public record KnowledgeDocumentView(String documentId, String externalDocumentId, String title,
        FundDocumentType documentType, String publisher, String sourceName, String sourceUri,
        LocalDate publishedDate, String activeVersionId, Set<String> fundCodes,
        List<KnowledgeVersionView> versions, Instant createdAt, Instant updatedAt) {}
