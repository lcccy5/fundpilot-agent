package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.util.List;

public record KnowledgeSearchResult(String retrievalId, List<RetrievedChunk> chunks, List<String> warnings) {
    public KnowledgeSearchResult {
        chunks=chunks==null?List.of():List.copyOf(chunks);
        warnings=warnings==null?List.of():List.copyOf(warnings);
    }
}
