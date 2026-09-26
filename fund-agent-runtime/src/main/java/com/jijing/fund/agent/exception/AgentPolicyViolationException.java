package com.jijing.fund.agent.exception;

/**
 * 输入、输出或执行权限违反安全策略时抛出，调用方应中止本轮。
 * 不携带结构化违规码，只有说明文本。
 */
public class AgentPolicyViolationException extends RuntimeException {

    /**
     * 用策略说明构造违规异常。
     * 说明为空时仍抛出，调用方无法从消息区分具体规则。
     */
    public AgentPolicyViolationException(String message) {
        super(message);
    }
}
