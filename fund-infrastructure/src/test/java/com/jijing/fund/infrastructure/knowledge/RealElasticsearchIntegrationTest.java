package com.jijing.fund.infrastructure.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.domain.*;
import java.net.*;
import java.net.http.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="RUN_ELASTICSEARCH_INTEGRATION_TESTS",matches="true")
class RealElasticsearchIntegrationTest {
    @Test void bulkBm25KnnAndFilterWorkAgainstRealCluster()throws Exception{
        String uri=System.getenv().getOrDefault("ELASTICSEARCH_URI","http://127.0.0.1:9200");String name="fund_document_it_"+UUID.randomUUID().toString().replace("-","");
        try{var index=new ElasticsearchHttpDocumentSearchIndex(uri,name,2,"it-v1",new ObjectMapper().findAndRegisterModules(),System.getenv("ELASTICSEARCH_USERNAME"),System.getenv("ELASTICSEARCH_PASSWORD"),System.getenv("ELASTICSEARCH_API_KEY"),null,null);var c=new DocumentChunk("chunk-it","doc-it","version-it","基金经理强调控制回撤和长期投资","策略",1,1,0,20,"chunk-v1","hash",Set.of("000001"),FundDocumentType.QUARTERLY_REPORT,"季报",LocalDate.of(2026,6,30),"integration",null);index.index(List.of(new IndexedChunk(c,new float[]{1,0},"embed-it")));var q=new KnowledgeSearchQuery("控制回撤",Set.of("000001"),Set.of(FundDocumentType.QUARTERLY_REPORT),null,null,5);assertThat(index.lexicalSearch(q,5)).isNotEmpty();assertThat(index.vectorSearch(q,new float[]{1,0},5)).isNotEmpty();}
        finally{HttpRequest req=HttpRequest.newBuilder(URI.create(uri+"/"+name)).DELETE().build();HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.discarding());}
    }
}
