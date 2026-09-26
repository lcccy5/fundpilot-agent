package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.FundQueryUseCase;
import com.jijing.fund.application.dto.FundProfileResult;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 用切片核对基金资料查询成功时回传统一信封和请求号。
 */
@WebMvcTest(controllers=FundQueryController.class)
@Import({FundQueryController.class, RequestIdFilter.class, GlobalExceptionHandler.class})
class FundQueryControllerTest {
    @Autowired MockMvc mvc;
    @MockBean FundQueryUseCase useCase;

    /**
     * 成功响应携带 SUCCESS、原请求号和基金代码。
     */
    @Test void returnsUnifiedEnvelopeAndRequestId() throws Exception {
        when(useCase.getProfile("000001")).thenReturn(new FundProfileResult("000001", "华夏成长混合", "混合型",
                "华夏基金", "王明", LocalDate.of(2001,12,18), "mock", Instant.now(), Instant.now(), "FRESH"));
        mvc.perform(get("/api/v1/funds/000001").header("X-Request-Id", "req-001"))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-Id", "req-001"))
                .andExpect(jsonPath("$.code").value("SUCCESS")).andExpect(jsonPath("$.requestId").value("req-001"))
                .andExpect(jsonPath("$.data.fundCode").value("000001"));
    }

    /**
     * 给接口切片提供一个不扫描全应用的启动配置。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication { }
}
