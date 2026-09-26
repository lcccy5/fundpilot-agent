package com.jijing.fund.infrastructure.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.research.model.ExchangeSecurityCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TencentMarketQuoteProviderReadabilityGapTest {
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
    void missingBodyIsAnEmptyQuote() {
        server.enqueue(new MockResponse().setResponseCode(204));
        TencentMarketQuoteProvider provider = provider();

        assertThat(provider.latestQuote(new ExchangeSecurityCode("sz", "159819"))).isEmpty();
    }

    @Test
    void emptyQuotedPayloadIsAContractError() {
        server.enqueue(new MockResponse().setBody("\"\""));
        TencentMarketQuoteProvider provider = provider();

        assertThatThrownBy(() -> provider.latestQuote(new ExchangeSecurityCode("sz", "159819")))
                .isInstanceOf(ExternalDataSourceException.class)
                .extracting("errorCode").isEqualTo("PROVIDER_CONTRACT_ERROR");
    }

    @Test
    void connectionRefusedIsQuoteUnavailable() throws Exception {
        int port = server.getPort();
        server.shutdown();
        TencentMarketQuoteProvider provider = new TencentMarketQuoteProvider("http://127.0.0.1:" + port, clock);

        assertThatThrownBy(() -> provider.latestQuote(new ExchangeSecurityCode("sz", "159819")))
                .isInstanceOf(ExternalDataSourceException.class)
                .hasMessage("Unable to load market quote")
                .extracting("errorCode").isEqualTo("MARKET_QUOTE_UNAVAILABLE");
    }

    private TencentMarketQuoteProvider provider() {
        return new TencentMarketQuoteProvider(server.url("/").toString(), clock);
    }
}
