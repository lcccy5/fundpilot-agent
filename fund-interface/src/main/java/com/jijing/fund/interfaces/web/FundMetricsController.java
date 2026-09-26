package com.jijing.fund.interfaces.web;

import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.application.FundMetricsQueryUseCase;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开的单基金区间指标。与资料查询一样允许匿名访问。
 * 日期缺失或格式错误返回 400；基金不存在返回 404；净值尚未就绪返回 409。
 * 不支持的净值口径返回 422。上游不可用返回 503，数据质量错误返回 502。
 */
@RestController
@RequestMapping("/api/v1/funds")
public class FundMetricsController {
    private final FundMetricsQueryUseCase useCase;

    /**
     * 绑定区间指标计算用例。
     */
    public FundMetricsController(FundMetricsQueryUseCase useCase) {
        this.useCase = useCase;
    }

    /**
     * 计算一只基金在给定区间和净值口径上的指标。口径可省略，由用例决定默认值。
     */
    @GetMapping("/{fundCode}/metrics")
    public ApiResponse<FundMetrics> metrics(@PathVariable("fundCode") String fundCode,
            @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "navBasis", required = false) String navBasis,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.calculate(fundCode, startDate, endDate, navBasis));
    }
}
