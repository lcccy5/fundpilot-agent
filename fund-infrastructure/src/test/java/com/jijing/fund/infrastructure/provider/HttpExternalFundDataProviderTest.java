package com.jijing.fund.infrastructure.provider;

import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.model.FundCode;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;

class HttpExternalFundDataProviderTest {
    private MockWebServer server;
    private HttpExternalFundDataProvider provider;

    @BeforeEach void setUp() throws Exception {
        server = new MockWebServer(); server.start();
        provider = new HttpExternalFundDataProvider(server.url("/").toString(), "secret-token", "contract-test",
                Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC), Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
    @AfterEach void tearDown() throws Exception { server.shutdown(); }

    @Test void mapsProfileWithoutLeakingProviderDto() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("""
            {"code":"000001","name":"测试基金","fundType":"混合型","managementCompany":"测试公司",
             "fundManager":"测试经理","establishedDate":"2020-01-01","sourceUpdatedAt":"2026-08-21T12:00:00Z"}
            """));
        var profile = provider.fetchProfile(new FundCode("000001")).orElseThrow();
        assertEquals("测试基金", profile.name()); assertEquals("contract-test", profile.dataSource());
        assertEquals("Bearer secret-token", server.takeRequest().getHeader("Authorization"));
    }

    @Test void mapsRateLimitToStableErrorCode() {
        server.enqueue(new MockResponse().setResponseCode(429));
        var ex = assertThrows(ExternalDataSourceException.class, () -> provider.fetchProfile(new FundCode("000001")));
        assertEquals("DATA_SOURCE_RATE_LIMITED", ex.errorCode());
    }
}
