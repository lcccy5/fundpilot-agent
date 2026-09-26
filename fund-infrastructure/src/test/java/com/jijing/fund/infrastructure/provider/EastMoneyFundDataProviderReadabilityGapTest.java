package com.jijing.fund.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.model.FundCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EastMoneyFundDataProviderReadabilityGapTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC);
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
    void blankShortNameIsAnEmptyProfile() {
        server.enqueue(new MockResponse().setBody("{\"Datas\":{\"SHORTNAME\":\"  \"}}"));
        EastMoneyFundDataProvider provider = provider();

        assertThat(provider.fetchProfile(new FundCode("000001"))).isEmpty();
    }

    @Test
    void emptyHistoryPayloadFailsBeforeRowsAreRead() {
        server.enqueue(new MockResponse().setBody(""));
        EastMoneyFundDataProvider provider = provider();

        assertThatThrownBy(() -> provider.fetchNavHistory(new FundCode("000001"), LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-24")))
                .isInstanceOf(ExternalDataSourceException.class)
                .hasMessage("Unable to load fund net value history")
                .hasCauseInstanceOf(ExternalDataSourceException.class)
                .extracting("errorCode").isEqualTo("DATA_SOURCE_UNAVAILABLE");
    }

    @Test
    void connectionRefusedFailsTheProfileCall() throws Exception {
        int port = server.getPort();
        server.shutdown();
        EastMoneyFundDataProvider provider = new EastMoneyFundDataProvider("http://127.0.0.1:" + port, clock, "device",
                "mobile");

        assertThatThrownBy(() -> provider.fetchProfile(new FundCode("000001")))
                .isInstanceOf(ExternalDataSourceException.class)
                .hasMessage("Unable to load fund profile")
                .extracting("errorCode").isEqualTo("DATA_SOURCE_UNAVAILABLE");
    }

    private EastMoneyFundDataProvider provider() {
        return new EastMoneyFundDataProvider(server.url("/").toString(), clock, "device", "mobile");
    }
}
