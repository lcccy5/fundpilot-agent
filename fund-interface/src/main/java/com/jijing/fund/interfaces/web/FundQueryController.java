package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.FundQueryUseCase;
import com.jijing.fund.application.dto.FundNavHistoryResult;
import com.jijing.fund.application.dto.FundProfileResult;
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
 * 公开的基金资料与净值查询。安全配置对 {@code GET /api/v1/funds/**} 允许匿名访问。
 * 代码或日期不合法、以及缺少日期参数时返回 400；基金不存在返回 404。
 * 外部数据源不可用返回 503，数据质量错误返回 502。
 */
@RestController
@RequestMapping("/api/v1/funds")
public class FundQueryController {
    private final FundQueryUseCase useCase;

    /**
     * 绑定基金资料与净值查询用例。
     */
    public FundQueryController(FundQueryUseCase useCase) {
        this.useCase = useCase;
    }

    /**
     * 按六位基金代码返回资料。代码非法返回 400，不存在返回 404，上游失败返回 502 或 503。
     */
    @GetMapping("/{fundCode}")
    public ApiResponse<FundProfileResult> profile(@PathVariable("fundCode") String fundCode, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.getProfile(fundCode));
    }

    /**
     * 返回闭区间内的净值序列。缺少或无法解析的日期返回 400，基金不存在返回 404。
     */
    @GetMapping("/{fundCode}/nav")
    public ApiResponse<FundNavHistoryResult> navHistory(@PathVariable("fundCode") String fundCode,
            @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.getNavHistory(fundCode, startDate, endDate));
    }
}
