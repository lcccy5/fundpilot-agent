package com.jijing.fund.infrastructure.provider;

import com.jijing.fund.domain.model.FundCode;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;

class EastMoneyFundDataProviderTest {
    private MockWebServer server; private EastMoneyFundDataProvider provider;
    @BeforeEach void setUp() throws Exception { server = new MockWebServer(); server.start(); provider = new EastMoneyFundDataProvider(server.url("/").toString(), Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC), "device", "mobile"); }
    @AfterEach void tearDown() throws Exception { server.shutdown(); }
    @Test void mapsEstimateProfileAndHistory() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json; charset=UTF-8").setBody("{\"Datas\":{\"FCODE\":\"000001\",\"SHORTNAME\":\"华夏成长混合\",\"FTYPE\":\"混合型\",\"JJGS\":\"华夏基金\",\"ESTABDATE\":\"2001-12-18\",\"FSRQ\":\"2026-08-21\"}}"));
        server.enqueue(new MockResponse().setBody("<meta content=\"基金经理王明的信息\"/>"));
        server.enqueue(new MockResponse().setBody("{\"Datas\":[{\"FSRQ\":\"2026-08-21\",\"DWJZ\":\"1.2345\",\"LJJZ\":\"1.5678\"}]}"));
        var profile=provider.fetchProfile(new FundCode("000001")).orElseThrow(); assertEquals("华夏成长混合", profile.name()); assertEquals("华夏基金", profile.managementCompany());
        assertEquals(1, provider.fetchNavHistory(new FundCode("000001"), LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-24")).size());
    }
}
