package com.jijing.fund.infrastructure.knowledge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.IndexedChunk;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ElasticsearchHttpDocumentSearchIndexReadabilityGapTest {
    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void readTimeoutDuringIndexCheckFailsConstruction() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertThatThrownBy(() -> new ElasticsearchHttpDocumentSearchIndex(server.url("/").toString(), "fund_docs", 2,
                "v1", new ObjectMapper(), null, null, null, Duration.ofMillis(200), Duration.ofMillis(200)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Elasticsearch request failed");
    }

    @Test
    void connectionRefusedFailsConstruction() throws Exception {
        int port = server.getPort();
        server.shutdown();

        assertThatThrownBy(() -> new ElasticsearchHttpDocumentSearchIndex("http://127.0.0.1:" + port, "fund_docs", 2,
                "v1", new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Elasticsearch request failed");
    }

    @Test
    void emptyBulkBodyIsRejected() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"acknowledged\":true}"));
        ElasticsearchHttpDocumentSearchIndex index = new ElasticsearchHttpDocumentSearchIndex(server.url("/").toString(),
                "fund_docs", 2, "v1", new ObjectMapper());
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{"));

        assertThatThrownBy(() -> index.index(List.of(chunk())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid Elasticsearch bulk response");
    }

    private static IndexedChunk chunk() {
        DocumentChunk document = new DocumentChunk("chunk-1", "doc-1", "ver-1", "正文", "标题", 1, 1, 0, 2, "chunk-v1",
                "hash", Set.of("000001"), FundDocumentType.QUARTERLY_REPORT, "季报", LocalDate.of(2026, 6, 30),
                "manual", null);
        return new IndexedChunk(document, new float[] {1f, 0f}, "embed-v1");
    }
}
