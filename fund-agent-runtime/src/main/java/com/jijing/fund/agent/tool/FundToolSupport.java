package com.jijing.fund.agent.tool;

import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import jakarta.validation.*;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ToolContext;
import com.jijing.fund.domain.identity.AuthenticatedUser;

/** 实现 FundToolSupport 所代表的 Agent 运行时职责。 */
final class FundToolSupport {
    
    /** 执行该 Agent 运行时组件中的 FundToolSupport 操作。 */
    private FundToolSupport() {}
    static AgentExecutionTrace trace(ToolContext context) {
        Object value = context.getContext().get(AgentExecutionTrace.TOOL_CONTEXT_KEY);
        if (value instanceof AgentExecutionTrace trace) return trace;
        throw new IllegalStateException("Agent execution trace is required");
    }
    static AuthenticatedUser user(ToolContext context) {
        Object value=context.getContext().get(AgentExecutionTrace.USER_CONTEXT_KEY);
        if(value instanceof AuthenticatedUser user)return user;
        throw new IllegalStateException("authenticated user context is required");
    }
    static void validate(Validator validator, Object input) {
        var violations = validator.validate(input);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
    }
    static String safeMessage(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.substring(0, Math.min(message.length(), 300));
    }
    static String validationMessage(ConstraintViolationException error) {
        return error.getConstraintViolations().stream().map(v -> v.getPropertyPath()+" "+v.getMessage()).sorted().collect(Collectors.joining("; "));
    }
}
