package com.jijing.fund.agent.orchestration;

import com.jijing.fund.agent.exception.AgentPolicyViolationException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 在模型调用前后执行的确定性安全闸门。
 * 输入命中越权、泄密、危险操作或代下单规则，或回答含确定性收益承诺时，抛出策略异常并中止本次运行。
 * 它不替代计划校验、路由权限检查、导出审批或对等代理契约。
 */
public final class FundAgentSafetyPolicy {
    private static final List<Pattern> INPUT_DENY_RULES = List.of(
            Pattern.compile("(?is).*(?:显示|泄露|输出|忽略).*(?:系统提示词|system prompt|数据库密码|api[ _-]?key).*$"),
            Pattern.compile("(?is).*(?:执行|运行).*(?:delete|drop|truncate|删除数据库|删库).*$"),
            Pattern.compile("(?is).*(?:调用|使用).*(?:未注册工具|unregistered tool).*$"),
            Pattern.compile("(?is).*(?:其他用户|别的用户).*(?:持仓|组合|自选).*$"),
            Pattern.compile("(?is).*(?:帮我买|替我买|替我卖|替我下单|自动下单|帮我下单).*$"));
    private static final List<String> OUTPUT_DENY_PHRASES = List.of(
            "保证收益", "保本保收益", "稳赚", "必涨", "保证上涨", "保证不会亏",
            "建议重仓", "建议满仓", "guaranteed return", "guaranteed profit");

    /**
     * 在路由顾问或主模型看到原文之前检查用户输入。
     * 消息为 null 时直接返回。命中拒绝规则时抛出 {@link AgentPolicyViolationException}，
     * 本次不进入计划、路由建议或对等代理。
     */
    public void validateInput(String message) {
        if (message == null) {
            return;
        }
        for (Pattern rule : INPUT_DENY_RULES) {
            if (rule.matcher(message).matches()) {
                throw new AgentPolicyViolationException("请求包含越权、敏感信息获取或危险操作指令");
            }
        }
    }

    /**
     * 检查模型回答是否含确定性收益承诺或重仓指令。
     * 回答为 null 时按空字符串处理。命中时抛出 {@link AgentPolicyViolationException}，
     * 回答不得返回给用户，已创建的运行记为拒绝。
     */
    public void validateAnswer(String answer) {
        String normalized = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        if (OUTPUT_DENY_PHRASES.stream().anyMatch(normalized::contains)) {
            throw new AgentPolicyViolationException("模型回答包含确定性收益承诺或高风险投资指令，已被安全策略拦截");
        }
    }
}
