package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.risk.RiskProfileUseCase;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.risk.RiskProfile;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 风险问卷与当前用户的风险画像。
 * 问卷本身不读取登录用户，但安全配置仍要求认证后才能访问；匿名请求在过滤器返回 401。
 * 读取和提交画像必须解析出当前用户，缺失时返回 401。版本号为空返回 400。
 * 答案不合法返回 400。用例抛出的未分类异常返回 500。
 */
@RestController
@RequestMapping("/api/v1")
public class RiskProfileController {
    private final RiskProfileUseCase useCase;

    /**
     * 绑定问卷读取与画像提交用例。
     */
    public RiskProfileController(RiskProfileUseCase useCase) {
        this.useCase = useCase;
    }

    /**
     * 返回当前启用的问卷题目。控制器不区分调用者身份。
     */
    @GetMapping("/risk-questionnaire")
    public ApiResponse<RiskProfileUseCase.Questionnaire> questionnaire(HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.questionnaire());
    }

    /**
     * 返回当前用户已保存的风险画像。未登录返回 401；画像参数不合法返回 400。
     */
    @GetMapping("/users/me/risk-profile")
    public ApiResponse<RiskProfile> current(@CurrentUser AuthenticatedUser user, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.current(user));
    }

    /**
     * 用一份答卷覆盖当前用户的风险画像。版本号空白返回 400，未登录返回 401。
     */
    @PutMapping("/users/me/risk-profile")
    public ApiResponse<RiskProfile> submit(@CurrentUser AuthenticatedUser user, @Valid @RequestBody RiskBody body,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request),
                useCase.submit(user, body.questionnaireVersion(), body.answers()));
    }

    /**
     * 风险画像提交体。答案映射允许为空，由用例判断是否可计分。
     */
    public record RiskBody(@NotBlank String questionnaireVersion, Map<String, Integer> answers) {}
}
