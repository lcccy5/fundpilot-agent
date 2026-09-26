package com.jijing.fund.infrastructure.research;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.model.FundCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EastMoneyFundDiscoveryProviderReadabilityGapTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-27T02:00:00Z"), ZoneOffset.UTC);
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
    void emptyBodyCannotBeResolved() {
        server.enqueue(new MockResponse().setBody(""));
        EastMoneyFundDiscoveryProvider provider = provider();

        assertThatThrownBy(() -> provider.findLinkedExchangeFund(new FundCode("000001")))
                .isInstanceOf(ExternalDataSourceException.class)
                .hasMessage("Unable to resolve linked exchange fund")
                .extracting("errorCode").isEqualTo("FUND_DISCOVERY_UNAVAILABLE");
    }

    @Test
    void connectionRefusedCannotBeResolved() throws Exception {
        int port = server.getPort();
        server.shutdown();
        EastMoneyFundDiscoveryProvider provider = new EastMoneyFundDiscoveryProvider("http://127.0.0.1:" + port,
                new ObjectMapper(), clock);

        assertThatThrownBy(() -> provider.findLinkedExchangeFund(new FundCode("000001")))
                .isInstanceOf(ExternalDataSourceException.class)
                .hasMessage("Unable to resolve linked exchange fund")
                .extracting("errorCode").isEqualTo("FUND_DISCOVERY_UNAVAILABLE");
    }

    private EastMoneyFundDiscoveryProvider provider() {
        return new EastMoneyFundDiscoveryProvider(server.url("/").toString(), new ObjectMapper(), clock);
    }
}
