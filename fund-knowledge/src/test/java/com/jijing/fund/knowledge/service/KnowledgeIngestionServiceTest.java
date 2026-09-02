package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.domain.*;
import com.jijing.fund.knowledge.port.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIngestionServiceTest {
    @Test void registrationReturnsBeforeHeavyPipeline(){FakeRepository repo=new FakeRepository();RawDocumentStore raw=new RawDocumentStore(){public String store(String hash,String name,byte[]bytes){return hash+".txt";}public byte[]read(String key){return new byte[0];}};var service=new KnowledgeIngestionService(repo,raw,Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"),ZoneOffset.UTC));var command=new RegisterDocumentCommand("ext-1","季度报告",FundDocumentType.QUARTERLY_REPORT,"基金公司","test",URI.create("https://example.test/q.pdf"),LocalDate.of(2026,6,30),Set.of("000001"),"q.txt","text/plain","正文".getBytes());var result=service.ingest(command);assertThat(result.status()).isEqualTo(IngestionStatus.REGISTERED);assertThat(repo.transitions).isEmpty();}
    private static final class FakeRepository implements DocumentMetadataRepository {private final List<IngestionStatus>transitions=new ArrayList<>();public DocumentRegistrationResult register(RegisterDocumentCommand c,String h,String s,Instant n){return new DocumentRegistrationResult("document-1","version-1","job-1",IngestionStatus.REGISTERED,false);}public void transition(String v,IngestionStatus e,IngestionStatus n,Instant at){transitions.add(n);}public void updateParsed(String v,int p,long c,String pv,List<String>w,Instant a){}public void updateIndexed(String v,int c,String cv,String ev,String i,Instant a){}public void fail(String v,String j,IngestionStatus s,String e,String m,Instant a){throw new AssertionError("unexpected failure");}}
    private static final class FakeIndex implements DocumentSearchIndex {private final List<IndexedChunk>values=new ArrayList<>();private String activatedVersion;public String indexVersion(){return "index-v1";}public void index(List<IndexedChunk>c){values.addAll(c);}public void activate(String d,String v){activatedVersion=v;}public List<RetrievedChunk>lexicalSearch(KnowledgeSearchQuery q,int k){return List.of();}public List<RetrievedChunk>vectorSearch(KnowledgeSearchQuery q,float[]v,int k){return List.of();}}
}
