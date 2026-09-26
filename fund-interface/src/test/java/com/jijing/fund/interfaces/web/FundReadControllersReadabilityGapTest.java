package com.jijing.fund.interfaces.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jijing.fund.application.FundComparisonUseCase;
import com.jijing.fund.application.FundMetricsQueryUseCase;
import com.jijing.fund.application.FundQueryUseCase;
import com.jijing.fund.application.FundSyncUseCase;
import com.jijing.fund.application.exception.FundNotFoundException;
import com.jijing.fund.application.exception.InvalidFundQueryException;
import com.jijing.fund.application.exception.NavDataNotReadyException;
import com.jijing.fund.application.exception.NoOverlappingPeriodException;
import com.jijing.fund.application.exception.SyncAlreadyRunningException;
import com.jijing.fund.application.exception.UnsupportedNavBasisException;
import com.jijing.fund.domain.exception.ExternalDataSourceException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 基金查询、指标、对比和 local 同步的参数、缺失与上游失败。
 * 资料和净值的只读接口在安全配置中允许匿名，因此这里断言的是业务失败而不是 401。
 */
@WebMvcTest(controllers = {FundQueryController.class, FundMetricsController.class, FundComparisonController.class,
        FundSyncController.class})
@ActiveProfiles("local")
@Import({FundQueryController.class, FundMetricsController.class, FundComparisonController.class, FundSyncController.class,
        RequestIdFilter.class, GlobalExceptionHandler.class, FundQueryControllerTest.TestApplication.class})
class FundReadControllersReadabilityGapTest {
    @Autowired MockMvc mvc;
    @MockBean FundQueryUseCase queries;
    @MockBean FundMetricsQueryUseCase metrics;
    @MockBean FundComparisonUseCase comparisons;
    @MockBean FundSyncUseCase sync;

    /**
     * 基金不存在时资料接口返回 404，匿名请求也不会先被控制器拒绝。
     */
    @Test
    void profileNotFound() throws Exception {
        when(queries.getProfile("000001")).thenThrow(new FundNotFoundException("000001"));
        mvc.perform(get("/api/v1/funds/000001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FUND_NOT_FOUND"));
    }

    /**
     * 代码或窗口不合法时资料接口返回 400。
     */
    @Test
    void profileInvalidArgument() throws Exception {
        when(queries.getProfile("bad")).thenThrow(new InvalidFundQueryException("fund code is invalid"));
        mvc.perform(get("/api/v1/funds/bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 上游不可用时资料接口返回 503。
     */
    @Test
    void profileProviderUnavailable() throws Exception {
        when(queries.getProfile("000001")).thenThrow(new ExternalDataSourceException("PROVIDER_UNAVAILABLE", "down"));
        mvc.perform(get("/api/v1/funds/000001"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
    }

    /**
     * 数据质量错误返回 502，与普通不可用区分开。
     */
    @Test
    void profileDataQualityIsBadGateway() throws Exception {
        when(queries.getProfile("000001")).thenThrow(new ExternalDataSourceException("DATA_QUALITY_ERROR", "bad nav"));
        mvc.perform(get("/api/v1/funds/000001"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DATA_QUALITY_ERROR"));
    }

    /**
     * 净值查询缺少起始日期时返回 400。
     */
    @Test
    void navMissingStartDate() throws Exception {
        mvc.perform(get("/api/v1/funds/000001/nav").param("endDate", "2026-01-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 非 ISO 日期返回 400。
     */
    @Test
    void navMalformedDate() throws Exception {
        mvc.perform(get("/api/v1/funds/000001/nav")
                        .param("startDate", "2026/01/01")
                        .param("endDate", "2026-01-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 指标查询遇到不存在的基金时返回 404。
     */
    @Test
    void metricsNotFound() throws Exception {
        when(metrics.calculate(eq("000001"), any(), any(), any()))
                .thenThrow(new FundNotFoundException("000001"));
        mvc.perform(get("/api/v1/funds/000001/metrics")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-08-31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FUND_NOT_FOUND"));
    }

    /**
     * 净值还没准备好时指标接口返回 409。
     */
    @Test
    void metricsNavNotReady() throws Exception {
        when(metrics.calculate(eq("000001"), any(), any(), eq("ACCUMULATED_NAV")))
                .thenThrow(new NavDataNotReadyException("000001"));
        mvc.perform(get("/api/v1/funds/000001/metrics")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-08-31")
                        .param("navBasis", "ACCUMULATED_NAV"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NAV_DATA_NOT_READY"));
    }

    /**
     * 不支持的净值口径返回 422。
     */
    @Test
    void metricsUnsupportedBasis() throws Exception {
        when(metrics.calculate(eq("000001"), any(), any(), eq("UNIT_NAV")))
                .thenThrow(new UnsupportedNavBasisException("UNIT_NAV is not supported"));
        mvc.perform(get("/api/v1/funds/000001/metrics")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-08-31")
                        .param("navBasis", "UNIT_NAV"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_NAV_BASIS"));
    }

    /**
     * 指标上游不可用返回 503。
     */
    @Test
    void metricsProviderDown() throws Exception {
        when(metrics.calculate(eq("000001"), any(LocalDate.class), any(LocalDate.class), any()))
                .thenThrow(new ExternalDataSourceException("PROVIDER_UNAVAILABLE", "timeout"));
        mvc.perform(get("/api/v1/funds/000001/metrics")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-08-31"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
    }

    /**
     * 少于两只基金时对比请求返回 400。
     */
    @Test
    void comparisonRejectsSingleFund() throws Exception {
        mvc.perform(post("/api/v1/fund-comparisons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundCodes\":[\"000001\"],\"startDate\":\"2026-01-01\",\"endDate\":\"2026-08-31\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 对比窗口没有重叠净值时返回 422。
     */
    @Test
    void comparisonWithoutOverlap() throws Exception {
        when(comparisons.compare(any(), any(), any(), any())).thenThrow(new NoOverlappingPeriodException());
        mvc.perform(post("/api/v1/fund-comparisons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundCodes\":[\"000001\",\"110022\"],\"startDate\":\"2026-01-01\",\"endDate\":\"2026-08-31\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_OVERLAPPING_PERIOD"));
    }

    /**
     * 对比中的基金不存在时返回 404。
     */
    @Test
    void comparisonFundMissing() throws Exception {
        when(comparisons.compare(any(), any(), any(), any())).thenThrow(new FundNotFoundException("110022"));
        mvc.perform(post("/api/v1/fund-comparisons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundCodes\":[\"000001\",\"110022\"],\"startDate\":\"2026-01-01\",\"endDate\":\"2026-08-31\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FUND_NOT_FOUND"));
    }

    /**
     * 同步缺少日期时返回 400。
     */
    @Test
    void syncMissingDates() throws Exception {
        mvc.perform(post("/internal/v1/funds/000001/sync"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 同步目标不存在时返回 404。
     */
    @Test
    void syncFundMissing() throws Exception {
        when(sync.syncFund(eq("000001"), any(), any())).thenThrow(new FundNotFoundException("000001"));
        mvc.perform(post("/internal/v1/funds/000001/sync")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FUND_NOT_FOUND"));
    }

    /**
     * 同一基金已有同步在跑时返回 409。
     */
    @Test
    void syncAlreadyRunning() throws Exception {
        when(sync.syncFund(eq("000001"), any(), any())).thenThrow(new SyncAlreadyRunningException("000001"));
        mvc.perform(post("/internal/v1/funds/000001/sync")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-31"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYNC_ALREADY_RUNNING"));
    }

    /**
     * 同步上游数据质量错误返回 502。
     */
    @Test
    void syncDataQualityIsBadGateway() throws Exception {
        when(sync.syncFund(eq("000001"), any(), any()))
                .thenThrow(new ExternalDataSourceException("DATA_QUALITY_ERROR", "bad payload"));
        mvc.perform(post("/internal/v1/funds/000001/sync")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-31"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DATA_QUALITY_ERROR"));
    }
}
