package com.jijing.fund.agent.exception;

/**
 * 运行、计划或审批不存在，或不属于当前所有者时抛出。
 * 故意不区分缺失和越权，避免泄露其他用户的运行是否存在。
 */
public class AgentRunNotFoundException extends RuntimeException {

    /**
     * 用调用方准备的说明构造找不到运行的异常。
     * 说明为空时仍抛出。
     */
    public AgentRunNotFoundException(String message) {
        super(message);
    }
}
