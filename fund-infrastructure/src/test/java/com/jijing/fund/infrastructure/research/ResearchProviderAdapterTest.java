package com.jijing.fund.infrastructure.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.research.model.ExchangeSecurityCode;
import com.jijing.fund.domain.research.model.MarketDataKind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResearchProviderAdapterTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-27T02:00:00Z"), ZoneOffset.UTC);
    private MockWebServer server;

    @BeforeEach void start() throws Exception { server = new MockWebServer(); server.start(); }
    @AfterEach void stop() throws Exception { server.shutdown(); }

    @Test
    void resolvesLinkedEtfWithoutLeakingEastMoneyDto() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"Datas\":{\"ETFCODE\":\"159819\",\"ETFSHORTNAME\":\"人工智能ETF\"}}"));
        var provider = new EastMoneyFundDiscoveryProvider(server.url("/").toString(), new ObjectMapper(), clock);

        var value = provider.findLinkedExchangeFund(new FundCode("000001")).orElseThrow();

        assertThat(value.securityCode()).isEqualTo(new ExchangeSecurityCode("sz", "159819"));
        assertThat(value.provenance().dataKind()).isEqualTo(MarketDataKind.UNDERLYING_ETF_PROXY);
        assertThat(server.takeRequest().getPath()).startsWith("/FundMNewApi/FundMNInverstPosition");
    }

    @Test
    void returnsEmptyWhenLinkedEtfIsNotPresent() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"Datas\":{}}"));
        var provider = new EastMoneyFundDiscoveryProvider(server.url("/").toString(), new ObjectMapper(), clock);

        assertThat(provider.findLinkedExchangeFund(new FundCode("000001"))).isEmpty();
    }

    @Test
    void parsesTencentQuoteToTypedExchangeQuote() {
        String[] fields = new String[33];
        Arrays.fill(fields, "0");
        fields[1] = "人工智能ETF";
        fields[3] = "1.23";
        fields[4] = "1.20";
        fields[30] = "20260827100000";
        fields[31] = "0.03";
        fields[32] = "2.50";
        server.enqueue(new MockResponse().setBody("v_sz159819=\"" + String.join("~", fields) + "\";"));
        var provider = new TencentMarketQuoteProvider(server.url("/").toString(), clock);

        var quote = provider.latestQuote(new ExchangeSecurityCode("sz", "159819")).orElseThrow();

        assertThat(quote.currentPrice()).hasToString("1.23");
        assertThat(quote.provenance().dataKind()).isEqualTo(MarketDataKind.EXCHANGE_TRADED_QUOTE);
        assertThat(quote.quoteTime()).isEqualTo(Instant.parse("2026-08-27T02:00:00Z"));
    }

    @Test
    void rejectsMalformedTencentResponseAsProviderContractError() {
        server.enqueue(new MockResponse().setBody("not-a-quote"));
        var provider = new TencentMarketQuoteProvider(server.url("/").toString(), clock);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.latestQuote(new ExchangeSecurityCode("sz", "159819")))
                .isInstanceOf(ExternalDataSourceException.class).extracting("errorCode").isEqualTo("PROVIDER_CONTRACT_ERROR");
    }
}
