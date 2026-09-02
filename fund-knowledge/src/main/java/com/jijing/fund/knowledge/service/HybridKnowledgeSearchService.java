package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import com.jijing.fund.knowledge.port.*;
import java.util.*;

public final class HybridKnowledgeSearchService implements KnowledgeSearchUseCase {
    private final DocumentEmbeddingPort embeddings;private final DocumentSearchIndex index;private final DocumentReranker reranker;
    private final ReciprocalRankFusion rrf;private final ContextBudgetAllocator budget=new ContextBudgetAllocator();private final int channelTopK,fusedTopK,rerankTopK,maxContextTokens;
    public HybridKnowledgeSearchService(DocumentEmbeddingPort embeddings,DocumentSearchIndex index,DocumentReranker reranker,int channelTopK,int rrfK,int fusedTopK,int rerankTopK,int maxContextTokens){this.embeddings=embeddings;this.index=index;this.reranker=reranker;this.channelTopK=channelTopK;this.rrf=new ReciprocalRankFusion(rrfK);this.fusedTopK=fusedTopK;this.rerankTopK=rerankTopK;this.maxContextTokens=maxContextTokens;}
    @Override public KnowledgeSearchResult search(KnowledgeSearchQuery query){validate(query);List<RetrievedChunk>lexical=index.lexicalSearch(query,channelTopK);float[]vector=embeddings.embedQuery(query.query());List<RetrievedChunk>semantic=index.vectorSearch(query,vector,channelTopK);List<RetrievedChunk>fused=rrf.fuse(lexical,semantic,fusedTopK);List<String>warnings=new ArrayList<>();List<RetrievedChunk>reranked;
        try{reranked=reranker.rerank(query.query(),fused,rerankTopK);}catch(RuntimeException ex){warnings.add("RERANK_DEGRADED");reranked=fused;}
        return new KnowledgeSearchResult(UUID.randomUUID().toString(),budget.allocate(reranked,maxContextTokens,query.topK()),warnings);}
    private void validate(KnowledgeSearchQuery query){if(query==null||query.query()==null||query.query().isBlank()||query.query().length()>500)throw new IllegalArgumentException("query must contain 1 to 500 characters");if(query.fundCodes().stream().anyMatch(code->!code.matches("\\d{6}")))throw new IllegalArgumentException("fundCode must be 6 digits");if(query.publishedAfter()!=null&&query.publishedBefore()!=null&&query.publishedAfter().isAfter(query.publishedBefore()))throw new IllegalArgumentException("publishedAfter must not be after publishedBefore");}
}
