package com.jijing.fund.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.model.FundCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpExternalFundDataProviderReadabilityGapTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);
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
    void readTimeoutIsReportedAsDataSourceUnavailable() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        HttpExternalFundDataProvider provider = provider(Duration.ofMillis(200), Duration.ofMillis(200));

        assertThatThrownBy(() -> provider.fetchProfile(new FundCode("000001")))
                .isInstanceOf(ExternalDataSourceException.class)
                .hasMessage("Fund provider request timed out")
                .extracting("errorCode").isEqualTo("DATA_SOURCE_UNAVAILABLE");
    }

    @Test
    void connectionRefusedUsesTheSameTimeoutMessage() throws Exception {
        int port = server.getPort();
        server.shutdown();
        HttpExternalFundDataProvider provider = new HttpExternalFundDataProvider("http://127.0.0.1:" + port, "",
                "contract-test", clock, Duration.ofMillis(200), Duration.ofMillis(200));

        assertThatThrownBy(() -> provider.fetchNavHistory(new FundCode("000001"), LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-01-31")))
                .isInstanceOf(ExternalDataSourceException.class)
                .hasMessage("Fund provider request timed out")
                .extracting("errorCode").isEqualTo("DATA_SOURCE_UNAVAILABLE");
    }

    @Test
    void nullProfileBodyIsAQualityError() {
        server.enqueue(json("null"));
        HttpExternalFundDataProvider provider = provider(Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThatThrownBy(() -> provider.fetchProfile(new FundCode("000001")))
                .isInstanceOf(ExternalDataSourceException.class)
                .hasMessage("Empty profile response")
                .extracting("errorCode").isEqualTo("DATA_QUALITY_ERROR");
    }

    @Test
    void nullNavItemsAreAQualityError() {
        server.enqueue(json("{\"items\":null}"));
        HttpExternalFundDataProvider provider = provider(Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThatThrownBy(() -> provider.fetchNavHistory(new FundCode("000001"), LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-01-31")))
                .isInstanceOf(ExternalDataSourceException.class)
                .hasMessage("Empty nav response")
                .extracting("errorCode").isEqualTo("DATA_QUALITY_ERROR");
    }

    @Test
    void blankProfileNameIsAQualityError() {
        server.enqueue(json("{\"name\":\"  \"}"));
        HttpExternalFundDataProvider provider = provider(Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThatThrownBy(() -> provider.fetchProfile(new FundCode("000001")))
                .isInstanceOf(ExternalDataSourceException.class)
                .hasMessage("Missing field: name")
                .extracting("errorCode").isEqualTo("DATA_QUALITY_ERROR");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    private HttpExternalFundDataProvider provider(Duration connect, Duration read) {
        return new HttpExternalFundDataProvider(server.url("/").toString(), "", "contract-test", clock, connect, read);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
