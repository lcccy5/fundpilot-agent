package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.util.*;

public final class ContextBudgetAllocator {
    public List<RetrievedChunk> allocate(List<RetrievedChunk> candidates,int maxTokens,int topK){
        List<RetrievedChunk> result=new ArrayList<>();Set<String>contentHashes=new HashSet<>();int used=0;
        for(RetrievedChunk candidate:candidates){if(!contentHashes.add(candidate.chunk().contentSha256()))continue;int tokens=candidate.chunk().tokenCount();if(tokens>maxTokens-used)continue;
            if(!result.isEmpty()&&adjacent(result.getLast(),candidate)&&result.getLast().chunk().tokenCount()+tokens<=maxTokens){RetrievedChunk previous=result.removeLast();result.add(merge(previous,candidate));used+=tokens;continue;}
            if(result.size()>=topK)break;result.add(candidate);used+=tokens;}
        return List.copyOf(result);
    }
    private boolean adjacent(RetrievedChunk left,RetrievedChunk right){var a=left.chunk();var b=right.chunk();return a.versionId().equals(b.versionId())&&b.chunkOrder()==a.chunkOrder()+1&&b.pageStart()<=a.pageEnd()+1;}
    private RetrievedChunk merge(RetrievedChunk left,RetrievedChunk right){var a=left.chunk();var b=right.chunk();Set<String>channels=new LinkedHashSet<>(left.channels());channels.addAll(right.channels());String content=a.content()+"\n"+b.content();var chunk=new com.jijing.fund.knowledge.domain.DocumentChunk(a.chunkId()+"+"+b.chunkId(),a.documentId(),a.versionId(),content,a.headingPath(),Math.min(a.pageStart(),b.pageStart()),Math.max(a.pageEnd(),b.pageEnd()),a.chunkOrder(),a.tokenCount()+b.tokenCount(),a.chunkingVersion(),KnowledgeHash.sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),a.fundCodes(),a.documentType(),a.documentTitle(),a.publishedDate(),a.sourceName(),a.sourceUri());return new RetrievedChunk(chunk,Math.max(left.score(),right.score()),Math.min(left.rank(),right.rank()),channels);}
}
