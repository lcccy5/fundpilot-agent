package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.FundSyncUseCase;
import com.jijing.fund.application.dto.FundSyncResult;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅 local 配置启用的手工同步入口，路径在 {@code /internal/**} 下，要求分析师或管理员角色。
 * 未认证返回安全过滤器的 401；角色不足返回 403。日期缺失或非法返回 400。
 * 基金不存在返回 404；同一基金同步已在进行返回 409。上游不可用返回 503，数据质量错误返回 502。
 */
@RestController
@Profile("local")
@RequestMapping("/internal/v1/funds")
public class FundSyncController {
    private final FundSyncUseCase useCase;

    /**
     * 绑定单基金同步用例。
     */
    public FundSyncController(FundSyncUseCase useCase) {
        this.useCase = useCase;
    }

    /**
     * 按代码和日期窗口拉取并落库一只基金。调用方需要自行避免并发重复触发。
     */
    @PostMapping("/{fundCode}/sync")
    public ApiResponse<FundSyncResult> sync(@PathVariable("fundCode") String fundCode,
            @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.syncFund(fundCode, startDate, endDate));
    }
}
