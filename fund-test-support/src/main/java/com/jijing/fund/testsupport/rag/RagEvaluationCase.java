package com.jijing.fund.testsupport.rag;
import java.util.*;
public record RagEvaluationCase(String id,String question,Set<String>fundCodes,Set<String>documentTypes,
        Set<String>relevantChunkIds,Set<String>requiredEvidenceIds,List<String>expectedAnswerPoints,
        List<String>forbiddenClaims,boolean shouldAbstain){public RagEvaluationCase{fundCodes=copy(fundCodes);documentTypes=copy(documentTypes);relevantChunkIds=copy(relevantChunkIds);requiredEvidenceIds=copy(requiredEvidenceIds);expectedAnswerPoints=expectedAnswerPoints==null?List.of():List.copyOf(expectedAnswerPoints);forbiddenClaims=forbiddenClaims==null?List.of():List.copyOf(forbiddenClaims);}private static Set<String>copy(Set<String>v){return v==null?Set.of():Set.copyOf(v);}}
