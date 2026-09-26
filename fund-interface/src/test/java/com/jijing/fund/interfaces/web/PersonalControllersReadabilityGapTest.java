package com.jijing.fund.interfaces.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jijing.fund.application.portfolio.PortfolioConflictException;
import com.jijing.fund.application.portfolio.PortfolioException;
import com.jijing.fund.application.portfolio.PortfolioNotFoundException;
import com.jijing.fund.application.portfolio.PortfolioUseCase;
import com.jijing.fund.application.risk.RiskProfileException;
import com.jijing.fund.application.risk.RiskProfileUseCase;
import com.jijing.fund.application.watchlist.WatchlistConflictException;
import com.jijing.fund.application.watchlist.WatchlistNotFoundException;
import com.jijing.fund.application.watchlist.WatchlistUseCase;
import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 组合、自选和风险画像尚未覆盖的匿名、参数、缺失、冲突和上游失败。
 */
@WebMvcTest(controllers = {PortfolioController.class, WatchlistController.class, RiskProfileController.class})
@Import({PortfolioController.class, WatchlistController.class, RiskProfileController.class, RequestIdFilter.class,
        GlobalExceptionHandler.class, ReadabilityGapMvc.class, FundQueryControllerTest.TestApplication.class})
class PersonalControllersReadabilityGapTest {
    @Autowired MockMvc mvc;
    @MockBean PortfolioUseCase portfolios;
    @MockBean WatchlistUseCase watchlists;
    @MockBean RiskProfileUseCase risks;
    private static final String PORTFOLIO = "00000000-0000-0000-0000-0000000000a1";
    private final AuthenticatedUser user = new AuthenticatedUser(
            new UserId("00000000-0000-0000-0000-000000000003"), Set.of(UserRole.USER), "session-p");

    /**
     * 未登录不能列出组合。
     */
    @Test
    void portfolioListRequiresUser() throws Exception {
        mvc.perform(get("/api/v1/portfolios"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    /**
     * 组合名称为空白时返回 400。
     */
    @Test
    void portfolioCreateRejectsBlankName() throws Exception {
        mvc.perform(post("/api/v1/portfolios").principal(principal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 交易基金代码不是六位数字时返回 400。
     */
    @Test
    void portfolioAppendRejectsBadFundCode() throws Exception {
        mvc.perform(post("/api/v1/portfolios/" + PORTFOLIO + "/transactions").principal(principal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fundCode":"12","type":"SUBSCRIPTION","tradeDate":"2026-01-02","confirmDate":"2026-01-03",
                                 "shares":1,"grossAmount":1,"fee":0,"confirmedNav":1,"idempotencyKey":"k1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 幂等键冲突返回 409。
     */
    @Test
    void portfolioAppendConflict() throws Exception {
        when(portfolios.append(any(), any(), any())).thenThrow(new PortfolioConflictException("duplicate key"));
        mvc.perform(post("/api/v1/portfolios/" + PORTFOLIO + "/transactions").principal(principal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fundCode":"000001","type":"SUBSCRIPTION","tradeDate":"2026-01-02","confirmDate":"2026-01-03",
                                 "shares":1,"grossAmount":1,"fee":0,"confirmedNav":1,"idempotencyKey":"k1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERSONAL_CONFLICT"));
    }

    /**
     * 组合标识不是 UUID 时在进入用例前失败，目前被兜底成 500。
     */
    @Test
    void portfolioIdThatIsNotUuidIsInternalError() throws Exception {
        mvc.perform(get("/api/v1/portfolios/not-a-uuid/valuation").principal(principal()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    /**
     * 估值日期无法解析时返回 400。
     */
    @Test
    void portfolioValuationRejectsBadDate() throws Exception {
        mvc.perform(get("/api/v1/portfolios/" + PORTFOLIO + "/valuation").principal(principal()).param("asOfDate", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 组合不存在或属主不匹配时估值返回 404。
     */
    @Test
    void portfolioValuationMissing() throws Exception {
        when(portfolios.valuation(any(), any(), any())).thenThrow(new PortfolioNotFoundException("missing"));
        mvc.perform(get("/api/v1/portfolios/" + PORTFOLIO + "/valuation").principal(principal()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERSONAL_RESOURCE_NOT_FOUND"));
    }

    /**
     * 估值所依赖的净值上游失败时返回 503。
     */
    @Test
    void portfolioValuationProviderDown() throws Exception {
        when(portfolios.valuation(any(), any(), any()))
                .thenThrow(new ExternalDataSourceException("PROVIDER_UNAVAILABLE", "nav down"));
        mvc.perform(get("/api/v1/portfolios/" + PORTFOLIO + "/valuation").principal(principal()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
    }

    /**
     * 账单内容不合法时返回 400，而不是 500。
     */
    @Test
    void portfolioPreviewInvalidFile() throws Exception {
        when(portfolios.previewImport(any(), any(), any(), any())).thenThrow(new PortfolioException("unreadable sheet"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                        "/api/v1/portfolios/" + PORTFOLIO + "/imports/preview")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "a.csv", "text/csv", new byte[]{1}))
                        .principal(principal()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PERSONAL_INVALID_ARGUMENT"));
    }

    /**
     * 未登录不能列出自选。
     */
    @Test
    void watchlistListRequiresUser() throws Exception {
        mvc.perform(get("/api/v1/watchlists"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    /**
     * 自选名称为空白时返回 400。
     */
    @Test
    void watchlistCreateRejectsBlankName() throws Exception {
        mvc.perform(post("/api/v1/watchlists").principal(principal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 删除分组时缺少版本参数返回 400。
     */
    @Test
    void watchlistDeleteRequiresVersion() throws Exception {
        mvc.perform(delete("/api/v1/watchlists/group-1").principal(principal()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 分组版本冲突返回 409。
     */
    @Test
    void watchlistDeleteConflict() throws Exception {
        doThrow(new WatchlistConflictException("stale")).when(watchlists).delete(eq(user), eq("group-1"), eq(2L));
        mvc.perform(delete("/api/v1/watchlists/group-1").principal(principal()).param("version", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERSONAL_CONFLICT"));
    }

    /**
     * 基金代码格式不对时不能加入自选。
     */
    @Test
    void watchlistAddRejectsBadCode() throws Exception {
        mvc.perform(post("/api/v1/watchlists/group-1/items").principal(principal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundCode\":\"12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 条目不存在或不属于当前用户时移除返回 404。
     */
    @Test
    void watchlistRemoveMissing() throws Exception {
        when(watchlists.removeItem(eq(user), eq("group-1"), eq("item-1"), eq(3L)))
                .thenThrow(new WatchlistNotFoundException("missing"));
        mvc.perform(delete("/api/v1/watchlists/group-1/items/item-1").principal(principal()).param("version", "3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERSONAL_RESOURCE_NOT_FOUND"));
    }

    /**
     * 问卷控制器本身不解析登录用户；安全过滤器才拒绝匿名，切片里用例仍会被调用。
     */
    @Test
    void questionnaireDoesNotRequireResolvedUser() throws Exception {
        when(risks.questionnaire()).thenReturn(new RiskProfileUseCase.Questionnaire("v1", List.of()));
        mvc.perform(get("/api/v1/risk-questionnaire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("v1"));
    }

    /**
     * 读取画像必须有登录主体。
     */
    @Test
    void riskProfileRequiresUser() throws Exception {
        mvc.perform(get("/api/v1/users/me/risk-profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    /**
     * 画像参数不合法返回 400。
     */
    @Test
    void riskProfileInvalid() throws Exception {
        when(risks.current(any())).thenThrow(new RiskProfileException("answers incomplete"));
        mvc.perform(get("/api/v1/users/me/risk-profile").principal(principal()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PERSONAL_INVALID_ARGUMENT"));
    }

    /**
     * 问卷版本为空时提交返回 400。
     */
    @Test
    void riskSubmitRejectsBlankVersion() throws Exception {
        mvc.perform(put("/api/v1/users/me/risk-profile").principal(principal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionnaireVersion\":\"\",\"answers\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 提交过程中的未分类失败返回 500。
     */
    @Test
    void riskSubmitUnexpectedFailure() throws Exception {
        when(risks.submit(any(), any(), any())).thenThrow(new IllegalStateException("store down"));
        mvc.perform(put("/api/v1/users/me/risk-profile").principal(principal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionnaireVersion\":\"v1\",\"answers\":{}}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    /**
     * 构造当前测试用户的认证主体。
     */
    private UsernamePasswordAuthenticationToken principal() {
        return new UsernamePasswordAuthenticationToken(user, null, List.of());
    }
}
