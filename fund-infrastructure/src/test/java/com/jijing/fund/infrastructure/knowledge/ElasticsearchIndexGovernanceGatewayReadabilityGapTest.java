package com.jijing.fund.infrastructure.knowledge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ElasticsearchIndexGovernanceGatewayReadabilityGapTest {
    private static final String REBUILD = "00000000-0000-0000-0000-000000000001";
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
    void readTimeoutFailsRebuild() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        ElasticsearchIndexGovernanceGateway gateway = gateway(Duration.ofMillis(200), Duration.ofMillis(200));

        assertThatThrownBy(() -> gateway.rebuild(REBUILD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Elasticsearch governance request failed");
    }

    @Test
    void connectionRefusedFailsRebuild() throws Exception {
        int port = server.getPort();
        server.shutdown();
        ElasticsearchIndexGovernanceGateway gateway = new ElasticsearchIndexGovernanceGateway(
                "http://127.0.0.1:" + port, "fund_docs", new ObjectMapper(), null, null, null, Duration.ofMillis(200),
                Duration.ofMillis(200));

        assertThatThrownBy(() -> gateway.rebuild(REBUILD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Elasticsearch governance request failed");
    }

    @Test
    void emptyAliasBodyIsInvalid() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(""));
        ElasticsearchIndexGovernanceGateway gateway = gateway(Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThatThrownBy(() -> gateway.rebuild(REBUILD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid alias response");
    }

    private ElasticsearchIndexGovernanceGateway gateway(Duration connect, Duration read) {
        return new ElasticsearchIndexGovernanceGateway(server.url("/").toString(), "fund_docs", new ObjectMapper(),
                null, null, null, connect, read);
    }
}
