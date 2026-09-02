package com.jijing.fund.interfaces.web;

import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.application.FundMetricsQueryUseCase;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/funds")
public class FundMetricsController {
    private final FundMetricsQueryUseCase useCase;

    public FundMetricsController(FundMetricsQueryUseCase useCase) { this.useCase = useCase; }

    @GetMapping("/{fundCode}/metrics")
    public ApiResponse<FundMetrics> metrics(@PathVariable("fundCode") String fundCode,
            @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "navBasis", required = false) String navBasis,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.calculate(fundCode, startDate, endDate, navBasis));
    }
}
