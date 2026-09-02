package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.FundComparisonUseCase;
import com.jijing.fund.application.dto.FundComparisonResult;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/fund-comparisons")
public class FundComparisonController {
    private final FundComparisonUseCase useCase;

    public FundComparisonController(FundComparisonUseCase useCase) { this.useCase = useCase; }

    @PostMapping
    public ApiResponse<FundComparisonResult> compare(@Valid @RequestBody ComparisonRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request),
                useCase.compare(body.fundCodes(), body.startDate(), body.endDate(), body.navBasis()));
    }

    public record ComparisonRequest(
            @NotNull @Size(min = 2, max = 10) List<@Pattern(regexp = "\\d{6}") String> fundCodes,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            String navBasis) {}
}
