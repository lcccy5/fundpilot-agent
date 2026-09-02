package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.FundDocumentType;
import java.time.LocalDate;
import java.util.Set;

public record KnowledgeSearchQuery(String query, Set<String> fundCodes, Set<FundDocumentType> documentTypes,
        LocalDate publishedAfter, LocalDate publishedBefore, int topK) {
    public KnowledgeSearchQuery {
        fundCodes=fundCodes==null?Set.of():Set.copyOf(fundCodes);
        documentTypes=documentTypes==null?Set.of():Set.copyOf(documentTypes);
        topK=topK<=0?6:Math.min(topK,10);
    }
}
