package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.util.*;

public final class ReciprocalRankFusion {
    private final int rankConstant;
    public ReciprocalRankFusion(int rankConstant){if(rankConstant<1)throw new IllegalArgumentException("rankConstant must be positive");this.rankConstant=rankConstant;}
    public List<RetrievedChunk> fuse(List<RetrievedChunk> lexical,List<RetrievedChunk> vector,int topK){
        Map<String,Accumulator> values=new HashMap<>();accumulate(values,lexical,"bm25");accumulate(values,vector,"vector");
        List<Accumulator> sorted=values.values().stream().sorted(Comparator.comparingDouble(Accumulator::score).reversed().thenComparing(a->a.chunk.chunkId())).limit(topK).toList();
        List<RetrievedChunk> result=new ArrayList<>();for(int i=0;i<sorted.size();i++){Accumulator a=sorted.get(i);result.add(new RetrievedChunk(a.chunk,a.score,i+1,a.channels));}return List.copyOf(result);
    }
    private void accumulate(Map<String,Accumulator> values,List<RetrievedChunk> input,String channel){if(input==null)return;for(int i=0;i<input.size();i++){RetrievedChunk hit=input.get(i);int rank=hit.rank()>0?hit.rank():i+1;Accumulator a=values.computeIfAbsent(hit.chunk().chunkId(),ignored->new Accumulator(hit.chunk()));a.score+=1.0/(rankConstant+rank);a.channels.add(channel);}}
    private static final class Accumulator {private final com.jijing.fund.knowledge.domain.DocumentChunk chunk;private final Set<String>channels=new TreeSet<>();private double score;private Accumulator(com.jijing.fund.knowledge.domain.DocumentChunk chunk){this.chunk=chunk;}private double score(){return score;}}
}
