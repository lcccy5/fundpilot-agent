package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.FundQueryUseCase;
import com.jijing.fund.application.dto.*;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/funds")
public class FundQueryController {
    private final FundQueryUseCase useCase;
    public FundQueryController(FundQueryUseCase useCase) { this.useCase = useCase; }
    @GetMapping(params = "q")
    public ApiResponse<List<FundSearchHit>> search(@RequestParam("q") String query, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.searchByName(query));
    }
    @GetMapping("/{fundCode}")
    public ApiResponse<FundProfileResult> profile(@PathVariable("fundCode") String fundCode, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.getProfile(fundCode));
    }
    @GetMapping("/{fundCode}/nav")
    public ApiResponse<FundNavHistoryResult> navHistory(@PathVariable("fundCode") String fundCode,
            @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.getNavHistory(fundCode, startDate, endDate));
    }
}
