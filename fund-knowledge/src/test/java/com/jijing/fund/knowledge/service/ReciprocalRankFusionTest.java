package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {
    @Test void rewardsChunksReturnedByBothChannelsAndKeepsStableOrder(){
        RetrievedChunk a=hit(chunk("a"),1,"bm25"),b=hit(chunk("b"),2,"bm25"),c=hit(chunk("c"),1,"vector");
        var result=new ReciprocalRankFusion(60).fuse(List.of(a,b),List.of(hit(chunk("b"),2,"vector"),c),10);
        assertThat(result).extracting(r->r.chunk().chunkId()).containsExactly("b","a","c");
        assertThat(result.getFirst().channels()).containsExactlyInAnyOrder("bm25","vector");
    }
    private RetrievedChunk hit(DocumentChunk chunk,int rank,String channel){return new RetrievedChunk(chunk,1,rank,Set.of(channel));}
    private DocumentChunk chunk(String id){return new DocumentChunk(id,"d","v","content-"+id,"",1,1,1,10,"c1",id,Set.of("000001"),FundDocumentType.QUARTERLY_REPORT,"title",null,"test",null);}
}
