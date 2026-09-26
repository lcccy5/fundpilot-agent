package com.jijing.fund.agent.exception;

/**
 * 提交命令或审批参数不满足运行入口的前置条件时抛出。
 * 不表示运行已经创建失败，通常是在写入之前就拒绝。
 */
public class AgentInvalidArgumentException extends RuntimeException {

    /**
     * 用缺少或非法参数的说明构造异常。
     * 说明为空时仍抛出。
     */
    public AgentInvalidArgumentException(String message) {
        super(message);
    }
}
