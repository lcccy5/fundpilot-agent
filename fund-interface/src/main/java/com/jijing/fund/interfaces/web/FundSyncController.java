package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.FundSyncUseCase;
import com.jijing.fund.application.dto.FundSyncResult;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@RequestMapping("/internal/v1/funds")
public class FundSyncController {
    private final FundSyncUseCase useCase;
    public FundSyncController(FundSyncUseCase useCase) { this.useCase = useCase; }
    @PostMapping("/{fundCode}/sync")
    public ApiResponse<FundSyncResult> sync(@PathVariable("fundCode") String fundCode,
            @RequestParam("startDate") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.syncFund(fundCode, startDate, endDate));
    }
}
