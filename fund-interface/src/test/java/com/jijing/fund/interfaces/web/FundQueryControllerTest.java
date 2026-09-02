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

@WebMvcTest(controllers=FundQueryController.class)
@Import({FundQueryController.class, RequestIdFilter.class, GlobalExceptionHandler.class})
class FundQueryControllerTest {
    @Autowired MockMvc mvc;
    @MockBean FundQueryUseCase useCase;
    @Test void returnsUnifiedEnvelopeAndRequestId() throws Exception {
        when(useCase.getProfile("000001")).thenReturn(new FundProfileResult("000001", "华夏成长混合", "混合型",
                "华夏基金", "王明", LocalDate.of(2001,12,18), "mock", Instant.now(), Instant.now(), "FRESH"));
        mvc.perform(get("/api/v1/funds/000001").header("X-Request-Id", "req-001"))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-Id", "req-001"))
                .andExpect(jsonPath("$.code").value("SUCCESS")).andExpect(jsonPath("$.requestId").value("req-001"))
                .andExpect(jsonPath("$.data.fundCode").value("000001"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication { }
}
