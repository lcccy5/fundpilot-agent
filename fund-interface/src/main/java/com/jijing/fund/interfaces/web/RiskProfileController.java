package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.risk.RiskProfileUseCase;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.risk.RiskProfile;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RiskProfileController {
    private final RiskProfileUseCase useCase;
    public RiskProfileController(RiskProfileUseCase useCase){this.useCase=useCase;}
    @GetMapping("/risk-questionnaire") public ApiResponse<RiskProfileUseCase.Questionnaire> questionnaire(HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),useCase.questionnaire());}
    @GetMapping("/users/me/risk-profile") public ApiResponse<RiskProfile> current(@CurrentUser AuthenticatedUser user,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),useCase.current(user));}
    @PutMapping("/users/me/risk-profile") public ApiResponse<RiskProfile> submit(@CurrentUser AuthenticatedUser user,@Valid @RequestBody RiskBody body,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),useCase.submit(user,body.questionnaireVersion(),body.answers()));}
    public record RiskBody(@NotBlank String questionnaireVersion,Map<String,Integer> answers){}
}
