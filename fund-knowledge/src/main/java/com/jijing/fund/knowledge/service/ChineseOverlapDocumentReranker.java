package com.jijing.fund.knowledge.service;
import com.jijing.fund.knowledge.domain.RetrievedChunk;import com.jijing.fund.knowledge.port.DocumentReranker;import java.util.*;
/** Optional local reranker for experiments; keep disabled until the fixed dataset proves a gain. */
public final class ChineseOverlapDocumentReranker implements DocumentReranker {
 public List<RetrievedChunk>rerank(String query,List<RetrievedChunk>candidates,int topK){Set<String>q=grams(query);return candidates.stream().map(c->Map.entry(c,score(q,grams(c.chunk().content()))+c.score()*.05)).sorted(Map.Entry.<RetrievedChunk,Double>comparingByValue().reversed()).limit(topK).map(Map.Entry::getKey).toList();}
 private double score(Set<String>a,Set<String>b){if(a.isEmpty()||b.isEmpty())return 0;long hit=a.stream().filter(b::contains).count();return (double)hit/Math.sqrt((double)a.size()*b.size());}
 private Set<String>grams(String value){String text=value==null?"":value.replaceAll("\\s+","");Set<String>result=new LinkedHashSet<>();for(int i=0;i<text.length()-1;i++)result.add(text.substring(i,i+2));return result;}
}
