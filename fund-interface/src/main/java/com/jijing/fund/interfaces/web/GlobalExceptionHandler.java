package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.exception.*;
import com.jijing.fund.agent.exception.*;
import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.knowledge.exception.*;
import com.jijing.fund.interfaces.api.ApiResponse;
import com.jijing.fund.application.auth.AuthException;
import com.jijing.fund.application.portfolio.PortfolioException;
import com.jijing.fund.application.portfolio.PortfolioNotFoundException;
import com.jijing.fund.application.portfolio.PortfolioConflictException;
import com.jijing.fund.application.watchlist.WatchlistException;
import com.jijing.fund.application.watchlist.WatchlistNotFoundException;
import com.jijing.fund.application.watchlist.WatchlistConflictException;
import com.jijing.fund.application.risk.RiskProfileException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.slf4j.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(AuthException.class)
    ResponseEntity<ApiResponse<Void>> auth(AuthException ex,HttpServletRequest request){return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.failure("AUTH_FAILED",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> denied(AccessDeniedException ex,HttpServletRequest request){return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.failure("AUTH_REQUIRED","authentication required",RequestIdFilter.get(request)));}
    @ExceptionHandler(PortfolioNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> portfolioMissing(PortfolioNotFoundException ex,HttpServletRequest request){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("PERSONAL_RESOURCE_NOT_FOUND",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(WatchlistNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> watchlistMissing(WatchlistNotFoundException ex,HttpServletRequest request){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("PERSONAL_RESOURCE_NOT_FOUND",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler({PortfolioConflictException.class,WatchlistConflictException.class})
    ResponseEntity<ApiResponse<Void>> personalConflict(RuntimeException ex,HttpServletRequest request){return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure("PERSONAL_CONFLICT",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler({PortfolioException.class,WatchlistException.class,RiskProfileException.class})
    ResponseEntity<ApiResponse<Void>> personalInvalid(RuntimeException ex,HttpServletRequest request){return ResponseEntity.badRequest().body(ApiResponse.failure("PERSONAL_INVALID_ARGUMENT",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(AgentDisabledException.class)
    ResponseEntity<ApiResponse<Void>> agentDisabled(AgentDisabledException ex,HttpServletRequest request){return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.failure("AGENT_DISABLED",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(AgentModelUnavailableException.class)
    ResponseEntity<ApiResponse<Void>> modelUnavailable(AgentModelUnavailableException ex,HttpServletRequest request){return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.failure("MODEL_UNAVAILABLE",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(AgentRunNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> agentRunMissing(AgentRunNotFoundException ex,HttpServletRequest request){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("AGENT_RUN_NOT_FOUND",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(com.jijing.fund.agent.planning.PlanValidationException.class)
    ResponseEntity<ApiResponse<Void>> planInvalid(com.jijing.fund.agent.planning.PlanValidationException ex,HttpServletRequest request){return ResponseEntity.badRequest().body(ApiResponse.failure("PLAN_INVALID",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(AgentInvalidArgumentException.class)
    ResponseEntity<ApiResponse<Void>> agentInvalid(AgentInvalidArgumentException ex,HttpServletRequest request){return ResponseEntity.badRequest().body(ApiResponse.failure("AGENT_INVALID_ARGUMENT",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(AgentExecutionLimitException.class)
    ResponseEntity<ApiResponse<Void>> agentLimit(AgentExecutionLimitException ex,HttpServletRequest request){return ResponseEntity.unprocessableEntity().body(ApiResponse.failure("AGENT_EXECUTION_LIMIT",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(AgentPolicyViolationException.class)
    ResponseEntity<ApiResponse<Void>> agentPolicy(AgentPolicyViolationException ex,HttpServletRequest request){return ResponseEntity.unprocessableEntity().body(ApiResponse.failure("AGENT_POLICY_VIOLATION",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(AgentEvidenceViolationException.class)
    ResponseEntity<ApiResponse<Void>> agentEvidence(AgentEvidenceViolationException ex,HttpServletRequest request){return ResponseEntity.unprocessableEntity().body(ApiResponse.failure("AGENT_EVIDENCE_VIOLATION",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(KnowledgeInvalidArgumentException.class)
    ResponseEntity<ApiResponse<Void>> knowledgeInvalid(KnowledgeInvalidArgumentException ex,HttpServletRequest request){return ResponseEntity.badRequest().body(ApiResponse.failure("KNOWLEDGE_INVALID_ARGUMENT",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(KnowledgeUnavailableException.class)
    ResponseEntity<ApiResponse<Void>> knowledgeUnavailable(KnowledgeUnavailableException ex,HttpServletRequest request){return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.failure("KNOWLEDGE_UNAVAILABLE",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(KnowledgeNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> knowledgeNotFound(KnowledgeNotFoundException ex,HttpServletRequest request){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("KNOWLEDGE_NOT_FOUND",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(KnowledgeConflictException.class)
    ResponseEntity<ApiResponse<Void>> knowledgeConflict(KnowledgeConflictException ex,HttpServletRequest request){return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure("KNOWLEDGE_CONFLICT",ex.getMessage(),RequestIdFilter.get(request)));}
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage()).orElse("Invalid request");
        return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_ARGUMENT", message, RequestIdFilter.get(request)));
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiResponse<Void>> malformedRequest(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_ARGUMENT", ex.getMessage(), RequestIdFilter.get(request)));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> noResource(NoResourceFoundException ex,HttpServletRequest request){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("NOT_FOUND","Resource not found",RequestIdFilter.get(request)));}
    @ExceptionHandler(InvalidFundQueryException.class)
    ResponseEntity<ApiResponse<Void>> invalid(InvalidFundQueryException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_ARGUMENT", ex.getMessage(), RequestIdFilter.get(request)));
    }
    @ExceptionHandler(FundNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> notFound(FundNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("FUND_NOT_FOUND", ex.getMessage(), RequestIdFilter.get(request)));
    }
    @ExceptionHandler(SyncAlreadyRunningException.class)
    ResponseEntity<ApiResponse<Void>> conflict(SyncAlreadyRunningException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure("SYNC_ALREADY_RUNNING", ex.getMessage(), RequestIdFilter.get(request)));
    }
    @ExceptionHandler(NavDataNotReadyException.class)
    ResponseEntity<ApiResponse<Void>> navNotReady(NavDataNotReadyException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure("NAV_DATA_NOT_READY", ex.getMessage(), RequestIdFilter.get(request)));
    }
    @ExceptionHandler(UnsupportedNavBasisException.class)
    ResponseEntity<ApiResponse<Void>> unsupportedBasis(UnsupportedNavBasisException ex, HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity().body(ApiResponse.failure("UNSUPPORTED_NAV_BASIS", ex.getMessage(), RequestIdFilter.get(request)));
    }
    @ExceptionHandler(NoOverlappingPeriodException.class)
    ResponseEntity<ApiResponse<Void>> noOverlap(NoOverlappingPeriodException ex, HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity().body(ApiResponse.failure("NO_OVERLAPPING_PERIOD", ex.getMessage(), RequestIdFilter.get(request)));
    }
    @ExceptionHandler(ExternalDataSourceException.class)
    ResponseEntity<ApiResponse<Void>> provider(ExternalDataSourceException ex, HttpServletRequest request) {
        HttpStatus status = "DATA_QUALITY_ERROR".equals(ex.errorCode()) ? HttpStatus.BAD_GATEWAY : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(ApiResponse.failure(ex.errorCode(), ex.getMessage(), RequestIdFilter.get(request)));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unknown(Exception ex, HttpServletRequest request) {
        log.error("requestId={} operation=api result=failed errorCode=INTERNAL_ERROR", RequestIdFilter.get(request), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure("INTERNAL_ERROR", "Internal server error", RequestIdFilter.get(request)));
    }
}
