package com.jijing.fund.infrastructure.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.domain.*;
import java.time.LocalDate;
import java.util.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchHttpDocumentSearchIndexTest {
    private MockWebServer server;
    @BeforeEach void start()throws Exception{server=new MockWebServer();server.start();}
    @AfterEach void stop()throws Exception{server.shutdown();}
    @Test void createsMappingAndBulkIndexesVersionedChunk()throws Exception{server.enqueue(new MockResponse().setResponseCode(404));server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"acknowledged\":true}"));server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"errors\":false}"));var index=new ElasticsearchHttpDocumentSearchIndex(server.url("/").toString(),"fund_document_test",2,"index-v1",new ObjectMapper().findAndRegisterModules());var chunk=new DocumentChunk("chunk-1","doc-1","ver-1","投资策略正文","投资策略",7,7,0,10,"chunk-v1","hash",Set.of("000001"),FundDocumentType.QUARTERLY_REPORT,"季度报告",LocalDate.of(2026,6,30),"manual","https://example.test/q.pdf");index.index(List.of(new IndexedChunk(chunk,new float[]{1,0},"embed-v1")));RecordedRequest head=server.takeRequest(),create=server.takeRequest(),bulk=server.takeRequest();assertThat(head.getMethod()).isEqualTo("HEAD");assertThat(create.getPath()).isEqualTo("/fund_document_test");assertThat(create.getBody().readUtf8()).contains("dense_vector").contains("dims");assertThat(bulk.getPath()).isEqualTo("/_bulk?refresh=wait_for");assertThat(bulk.getBody().readUtf8()).contains("chunk-1").contains("000001");}
}
