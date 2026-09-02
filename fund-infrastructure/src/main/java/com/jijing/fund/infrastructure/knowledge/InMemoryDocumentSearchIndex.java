package com.jijing.fund.infrastructure.knowledge;

import com.jijing.fund.knowledge.api.KnowledgeSearchQuery;
import com.jijing.fund.knowledge.domain.*;
import com.jijing.fund.knowledge.port.DocumentSearchIndex;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic local adapter used for development and tests; production uses the Elasticsearch adapter. */
public final class InMemoryDocumentSearchIndex implements DocumentSearchIndex {
    private final Map<String,IndexedChunk>values=new ConcurrentHashMap<>();private final String version;
    public InMemoryDocumentSearchIndex(String version){this.version=version;}
    @Override public String indexVersion(){return version;}
    @Override public void index(List<IndexedChunk>chunks){chunks.forEach(c->values.put(c.chunk().chunkId(),c));}
    @Override public void activate(String documentId,String versionId){values.entrySet().removeIf(e->e.getValue().chunk().documentId().equals(documentId)&&!e.getValue().chunk().versionId().equals(versionId));}
    @Override public List<RetrievedChunk> lexicalSearch(KnowledgeSearchQuery query,int topK){Set<String>terms=terms(query.query());return ranked(query,topK,indexed->{Set<String>content=terms(indexed.chunk().content());long overlap=terms.stream().filter(content::contains).count();return terms.isEmpty()?0:(double)overlap/terms.size();},"bm25");}
    @Override public List<RetrievedChunk> vectorSearch(KnowledgeSearchQuery query,float[]vector,int topK){return ranked(query,topK,indexed->cosine(vector,indexed.embedding()),"vector");}
    private List<RetrievedChunk> ranked(KnowledgeSearchQuery query,int topK,java.util.function.ToDoubleFunction<IndexedChunk>score,String channel){List<IndexedChunk>filtered=values.values().stream().filter(v->matches(query,v.chunk())).toList();List<Map.Entry<IndexedChunk,Double>>scored=filtered.stream().map(v->Map.entry(v,score.applyAsDouble(v))).filter(e->e.getValue()>0).sorted(Map.Entry.<IndexedChunk,Double>comparingByValue().reversed().thenComparing(e->e.getKey().chunk().chunkId())).limit(topK).toList();List<RetrievedChunk>result=new ArrayList<>();for(int i=0;i<scored.size();i++){var e=scored.get(i);result.add(new RetrievedChunk(e.getKey().chunk(),e.getValue(),i+1,Set.of(channel)));}return result;}
    private boolean matches(KnowledgeSearchQuery q,DocumentChunk c){if(!q.fundCodes().isEmpty()&&Collections.disjoint(q.fundCodes(),c.fundCodes()))return false;if(!q.documentTypes().isEmpty()&&!q.documentTypes().contains(c.documentType()))return false;if(q.publishedAfter()!=null&&(c.publishedDate()==null||c.publishedDate().isBefore(q.publishedAfter())))return false;return q.publishedBefore()==null||(c.publishedDate()!=null&&!c.publishedDate().isAfter(q.publishedBefore()));}
    private Set<String>terms(String value){Set<String>result=new HashSet<>();for(String term:value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))if(!term.isBlank())result.add(term);for(int i=0;i<value.length()-1;i++){String pair=value.substring(i,i+2);if(pair.codePoints().allMatch(Character::isLetterOrDigit))result.add(pair);}return result;}
    private double cosine(float[]a,float[]b){if(a.length==0||a.length!=b.length)return 0;double dot=0,aa=0,bb=0;for(int i=0;i<a.length;i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}return aa==0||bb==0?0:dot/(Math.sqrt(aa)*Math.sqrt(bb));}
}
