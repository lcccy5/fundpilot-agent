package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.RetrievedChunk;
import com.jijing.fund.knowledge.port.DocumentReranker;
import java.util.List;

public final class NoOpDocumentReranker implements DocumentReranker {
    @Override public List<RetrievedChunk> rerank(String query,List<RetrievedChunk> candidates,int topK){return candidates.stream().limit(topK).toList();}
}
