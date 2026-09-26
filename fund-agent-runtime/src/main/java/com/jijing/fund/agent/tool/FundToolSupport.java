package com.jijing.fund.agent.tool;

import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 工具适配器共用的轨迹、身份和参数校验。失败时抛出运行时异常，由各工具决定是转成信封还是继续抛出。
 */
final class FundToolSupport {
    /**
     * 禁止实例化。没有失败路径。
     */
    private FundToolSupport() {}

    /**
     * 取出本次运行的执行轨迹。上下文缺少轨迹或类型不对时抛出 IllegalStateException，调用方不应继续访问外部数据。
     */
    static AgentExecutionTrace trace(ToolContext context) {
        Object value = context.getContext().get(AgentExecutionTrace.TOOL_CONTEXT_KEY);
        if (value instanceof AgentExecutionTrace trace) {
            return trace;
        }
        throw new IllegalStateException("Agent execution trace is required");
    }

    /**
     * 取出服务端写入的登录用户。缺少用户或类型不对时抛出 IllegalStateException，禁止改用参数里的用户标识。
     */
    static AuthenticatedUser user(ToolContext context) {
        Object value = context.getContext().get(AgentExecutionTrace.USER_CONTEXT_KEY);
        if (value instanceof AuthenticatedUser user) {
            return user;
        }
        throw new IllegalStateException("authenticated user context is required");
    }

    /**
     * 执行 Bean Validation。存在约束违反时抛出 ConstraintViolationException，不修改入参。
     */
    static void validate(Validator validator, Object input) {
        var violations = validator.validate(input);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    /**
     * 截取最多 300 字的失败说明。异常没有 message 时退回异常类名，避免把 null 写进信封。
     */
    static String safeMessage(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.substring(0, Math.min(message.length(), 300));
    }

    /**
     * 把约束违反整理成稳定、可回显的字段说明。没有违反项时返回空字符串。
     */
    static String validationMessage(ConstraintViolationException error) {
        return error.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
    }
}
