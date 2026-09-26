package com.jijing.fund.agent.exception;

/**
 * 智能体未启用或尚未配置对话模型时抛出，阻止继续调用模型。
 * 消息固定说明需要配置模型并打开开关，不接受自定义原因。
 */
public class AgentDisabledException extends RuntimeException {

    /**
     * 构造一条固定说明，提示配置对话模型并启用智能体。
     * 不会失败。
     */
    public AgentDisabledException() {
        super("Fund Agent is disabled; configure a chat model and enable fund.agent.enabled");
    }
}
