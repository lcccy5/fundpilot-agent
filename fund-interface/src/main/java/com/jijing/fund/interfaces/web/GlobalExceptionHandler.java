package com.jijing.fund.interfaces.web;

import com.jijing.fund.agent.exception.AgentDisabledException;
import com.jijing.fund.agent.exception.AgentEvidenceViolationException;
import com.jijing.fund.agent.exception.AgentExecutionLimitException;
import com.jijing.fund.agent.exception.AgentInvalidArgumentException;
import com.jijing.fund.agent.exception.AgentModelUnavailableException;
import com.jijing.fund.agent.exception.AgentPolicyViolationException;
import com.jijing.fund.agent.exception.AgentRunNotFoundException;
import com.jijing.fund.agent.planning.PlanValidationException;
import com.jijing.fund.application.auth.AuthException;
import com.jijing.fund.application.exception.FundNotFoundException;
import com.jijing.fund.application.exception.InvalidFundQueryException;
import com.jijing.fund.application.exception.NavDataNotReadyException;
import com.jijing.fund.application.exception.NoOverlappingPeriodException;
import com.jijing.fund.application.exception.SyncAlreadyRunningException;
import com.jijing.fund.application.exception.UnsupportedNavBasisException;
import com.jijing.fund.application.portfolio.PortfolioConflictException;
import com.jijing.fund.application.portfolio.PortfolioException;
import com.jijing.fund.application.portfolio.PortfolioNotFoundException;
import com.jijing.fund.application.risk.RiskProfileException;
import com.jijing.fund.application.watchlist.WatchlistConflictException;
import com.jijing.fund.application.watchlist.WatchlistException;
import com.jijing.fund.application.watchlist.WatchlistNotFoundException;
import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.interfaces.api.ApiResponse;
import com.jijing.fund.knowledge.exception.KnowledgeConflictException;
import com.jijing.fund.knowledge.exception.KnowledgeInvalidArgumentException;
import com.jijing.fund.knowledge.exception.KnowledgeNotFoundException;
import com.jijing.fund.knowledge.exception.KnowledgeUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 把领域和框架异常收成统一响应信封。
 * 认证失败和缺少登录主体都返回 401；个人资源缺失返回 404，版本冲突返回 409，参数不合法返回 400。
 * 上游数据质量问题返回 502，上游不可用返回 503。未识别异常返回 500，响应正文不包含异常消息。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 登录、刷新或账号状态失败。对外统一为 401，避免细分账号是否存在。
     */
    @ExceptionHandler(AuthException.class)
    ResponseEntity<ApiResponse<Void>> auth(AuthException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.failure("AUTH_FAILED", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 参数解析阶段发现没有登录主体。这里返回 401，而不是 403。
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> denied(AccessDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.failure("AUTH_REQUIRED", "authentication required", RequestIdFilter.get(request)));
    }

    /**
     * 组合不存在，或不属于当前用户。两种情况都返回 404，避免泄露他人标识。
     */
    @ExceptionHandler(PortfolioNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> portfolioMissing(PortfolioNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure("PERSONAL_RESOURCE_NOT_FOUND", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 自选分组或条目不存在，或不属于当前用户。
     */
    @ExceptionHandler(WatchlistNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> watchlistMissing(WatchlistNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure("PERSONAL_RESOURCE_NOT_FOUND", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 组合或自选的版本、幂等键冲突。
     */
    @ExceptionHandler({PortfolioConflictException.class, WatchlistConflictException.class})
    ResponseEntity<ApiResponse<Void>> personalConflict(RuntimeException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure("PERSONAL_CONFLICT", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 组合、自选或风险画像的业务参数不合法。Bean 校验失败不走这里。
     */
    @ExceptionHandler({PortfolioException.class, WatchlistException.class, RiskProfileException.class})
    ResponseEntity<ApiResponse<Void>> personalInvalid(RuntimeException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("PERSONAL_INVALID_ARGUMENT", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 进程没有启用基金 Agent。
     */
    @ExceptionHandler(AgentDisabledException.class)
    ResponseEntity<ApiResponse<Void>> agentDisabled(AgentDisabledException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.failure("AGENT_DISABLED", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 对话模型暂时不可用。
     */
    @ExceptionHandler(AgentModelUnavailableException.class)
    ResponseEntity<ApiResponse<Void>> modelUnavailable(AgentModelUnavailableException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.failure("MODEL_UNAVAILABLE", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 运行不存在，或不属于路径中的当前用户。
     */
    @ExceptionHandler(AgentRunNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> agentRunMissing(AgentRunNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure("AGENT_RUN_NOT_FOUND", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 计划在执行前未通过校验。
     */
    @ExceptionHandler(PlanValidationException.class)
    ResponseEntity<ApiResponse<Void>> planInvalid(PlanValidationException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("PLAN_INVALID", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * Agent 入参不合法，且没有被 Bean 校验提前拦住。
     */
    @ExceptionHandler(AgentInvalidArgumentException.class)
    ResponseEntity<ApiResponse<Void>> agentInvalid(AgentInvalidArgumentException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("AGENT_INVALID_ARGUMENT", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 运行超出轮次、工具次数或令牌额度。
     */
    @ExceptionHandler(AgentExecutionLimitException.class)
    ResponseEntity<ApiResponse<Void>> agentLimit(AgentExecutionLimitException ex, HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiResponse.failure("AGENT_EXECUTION_LIMIT", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 运行违反策略约束，例如试图执行未批准的副作用。
     */
    @ExceptionHandler(AgentPolicyViolationException.class)
    ResponseEntity<ApiResponse<Void>> agentPolicy(AgentPolicyViolationException ex, HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiResponse.failure("AGENT_POLICY_VIOLATION", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 回答无法绑定到已记录的证据。
     */
    @ExceptionHandler(AgentEvidenceViolationException.class)
    ResponseEntity<ApiResponse<Void>> agentEvidence(AgentEvidenceViolationException ex, HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiResponse.failure("AGENT_EVIDENCE_VIOLATION", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 知识库入参不合法，包括上传类型、大小和检索条件。
     */
    @ExceptionHandler(KnowledgeInvalidArgumentException.class)
    ResponseEntity<ApiResponse<Void>> knowledgeInvalid(KnowledgeInvalidArgumentException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("KNOWLEDGE_INVALID_ARGUMENT", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 检索、导入或索引治理依赖当前不可用。
     */
    @ExceptionHandler(KnowledgeUnavailableException.class)
    ResponseEntity<ApiResponse<Void>> knowledgeUnavailable(KnowledgeUnavailableException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.failure("KNOWLEDGE_UNAVAILABLE", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 文档、版本、任务或重建标识不存在。
     */
    @ExceptionHandler(KnowledgeNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> knowledgeNotFound(KnowledgeNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure("KNOWLEDGE_NOT_FOUND", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 入库重试或索引切换与当前状态冲突。
     */
    @ExceptionHandler(KnowledgeConflictException.class)
    ResponseEntity<ApiResponse<Void>> knowledgeConflict(KnowledgeConflictException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure("KNOWLEDGE_CONFLICT", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * Bean 校验失败时只返回第一条字段错误，避免把全部约束一次性暴露给客户端。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("INVALID_ARGUMENT", message, RequestIdFilter.get(request)));
    }

    /**
     * 查询参数类型不匹配，或必填查询参数缺失。
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiResponse<Void>> malformedRequest(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("INVALID_ARGUMENT", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 没有匹配的接口或静态资源。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> noResource(NoResourceFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure("NOT_FOUND", "Resource not found", RequestIdFilter.get(request)));
    }

    /**
     * 基金代码、日期窗口等查询条件不合法。
     */
    @ExceptionHandler(InvalidFundQueryException.class)
    ResponseEntity<ApiResponse<Void>> invalid(InvalidFundQueryException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("INVALID_ARGUMENT", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 基金主数据不存在。
     */
    @ExceptionHandler(FundNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> notFound(FundNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure("FUND_NOT_FOUND", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 同一基金已经有同步在执行。
     */
    @ExceptionHandler(SyncAlreadyRunningException.class)
    ResponseEntity<ApiResponse<Void>> conflict(SyncAlreadyRunningException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure("SYNC_ALREADY_RUNNING", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 指标计算所需的净值还没有准备好。
     */
    @ExceptionHandler(NavDataNotReadyException.class)
    ResponseEntity<ApiResponse<Void>> navNotReady(NavDataNotReadyException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure("NAV_DATA_NOT_READY", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 请求的净值口径当前不能用于计算或对比。
     */
    @ExceptionHandler(UnsupportedNavBasisException.class)
    ResponseEntity<ApiResponse<Void>> unsupportedBasis(UnsupportedNavBasisException ex, HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiResponse.failure("UNSUPPORTED_NAV_BASIS", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 多只基金在请求窗口内没有共同的净值区间。
     */
    @ExceptionHandler(NoOverlappingPeriodException.class)
    ResponseEntity<ApiResponse<Void>> noOverlap(NoOverlappingPeriodException ex, HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiResponse.failure("NO_OVERLAPPING_PERIOD", ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 外部数据源失败。数据质量错误返回 502，其余错误码返回 503。
     */
    @ExceptionHandler(ExternalDataSourceException.class)
    ResponseEntity<ApiResponse<Void>> provider(ExternalDataSourceException ex, HttpServletRequest request) {
        HttpStatus status = "DATA_QUALITY_ERROR".equals(ex.errorCode())
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(ex.errorCode(), ex.getMessage(), RequestIdFilter.get(request)));
    }

    /**
     * 兜底异常。只记录请求号和异常，响应固定为内部错误，不把原始消息返回给客户端。
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unknown(Exception ex, HttpServletRequest request) {
        log.error("requestId={} operation=api result=failed errorCode=INTERNAL_ERROR", RequestIdFilter.get(request), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure("INTERNAL_ERROR", "Internal server error", RequestIdFilter.get(request)));
    }
}
