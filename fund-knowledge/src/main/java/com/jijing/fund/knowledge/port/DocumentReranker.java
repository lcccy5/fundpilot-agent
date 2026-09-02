package com.jijing.fund.knowledge.port;

import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.util.List;

public interface DocumentReranker {
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK);
}
