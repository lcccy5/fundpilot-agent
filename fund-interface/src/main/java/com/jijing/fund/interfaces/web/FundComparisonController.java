package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.FundComparisonUseCase;
import com.jijing.fund.application.dto.FundComparisonResult;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多基金同窗口指标对比。路由要求登录；匿名请求在安全过滤器处被拒绝。
 * 基金数量不在 2 到 10 之间、代码不是六位数字或日期缺失时返回 400。
 * 任一只基金不存在返回 404；区间没有重叠净值返回 422。上游失败返回 502 或 503。
 */
@RestController
@RequestMapping("/api/v1/fund-comparisons")
public class FundComparisonController {
    private final FundComparisonUseCase useCase;

    /**
     * 绑定对比用例。单基金计算失败会原样冒泡到统一异常映射。
     */
    public FundComparisonController(FundComparisonUseCase useCase) {
        this.useCase = useCase;
    }

    /**
     * 在同一日期窗口比较多只基金。请求体字段见 {@link ComparisonRequest}。
     */
    @PostMapping
    public ApiResponse<FundComparisonResult> compare(@Valid @RequestBody ComparisonRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request),
                useCase.compare(body.fundCodes(), body.startDate(), body.endDate(), body.navBasis()));
    }

    /**
     * 对比请求。净值口径可空，基金代码必须是六位数字。
     */
    public record ComparisonRequest(
            @NotNull @Size(min = 2, max = 10) List<@Pattern(regexp = "\\d{6}") String> fundCodes,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            String navBasis) {}
}
