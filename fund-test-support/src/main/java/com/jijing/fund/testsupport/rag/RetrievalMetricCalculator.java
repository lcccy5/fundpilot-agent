package com.jijing.fund.testsupport.rag;
import java.util.*;
public final class RetrievalMetricCalculator {
    public RetrievalMetrics calculate(Set<String>relevant,List<String>ranked,int k){if(relevant==null||relevant.isEmpty())return new RetrievalMetrics(1,1,1);List<String>top=ranked.stream().limit(k).toList();long hits=top.stream().filter(relevant::contains).distinct().count();double recall=(double)hits/relevant.size();double mrr=0,dcg=0;for(int i=0;i<top.size();i++)if(relevant.contains(top.get(i))){if(mrr==0)mrr=1d/(i+1);dcg+=1d/(Math.log(i+2)/Math.log(2));}double ideal=0;for(int i=0;i<Math.min(k,relevant.size());i++)ideal+=1d/(Math.log(i+2)/Math.log(2));return new RetrievalMetrics(recall,mrr,ideal==0?1:dcg/ideal);}
    public record RetrievalMetrics(double recallAtK,double mrrAtK,double ndcgAtK){}
}
