package com.jijing.fund.knowledge.port;

import com.jijing.fund.knowledge.api.KnowledgeSearchQuery;
import com.jijing.fund.knowledge.domain.*;
import java.util.List;

public interface DocumentSearchIndex {
    String indexVersion();
    void index(List<IndexedChunk> chunks);
    void activate(String documentId,String versionId);
    List<RetrievedChunk> lexicalSearch(KnowledgeSearchQuery query, int topK);
    List<RetrievedChunk> vectorSearch(KnowledgeSearchQuery query, float[] queryEmbedding, int topK);
}
