package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetAllocatorTest {
    @Test void respectsTokenBudgetAndRemovesDuplicateContent(){
        RetrievedChunk one=hit("a","same",40,1),duplicate=hit("b","same",40,2),large=hit("c","large",80,3);
        var result=new ContextBudgetAllocator().allocate(List.of(one,duplicate,large),100,5);
        assertThat(result).extracting(r->r.chunk().chunkId()).containsExactly("a");
    }
    private RetrievedChunk hit(String id,String hash,int tokens,int rank){var c=new DocumentChunk(id,"d","v",id,"",1,1,rank,tokens,"v1",hash,Set.of(),FundDocumentType.OTHER,"t",null,"s",null);return new RetrievedChunk(c,1,rank,Set.of("bm25"));}
}
