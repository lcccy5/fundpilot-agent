package com.jijing.fund.agent.capability;

/**
 * 由服务端注册的一项能力。规划结果只提供能力类型，不能选择实现类。
 * 执行失败时应抛出运行时异常，由工作者决定记失败还是因租约丢失而放弃。
 */
public interface AgentCapabilityExecutor {

    /**
     * 返回注册表用来匹配任务的能力类型。
     * 空类型会在注册时被拒绝，不会进入执行。
     */
    String capabilityType();

    /**
     * 在给定租约上下文中执行能力并返回产出地址和证据。
     * 输入不合法或下游失败时抛出运行时异常；上下文要求停止时不得继续外部调用。
     */
    CapabilityExecutionResult execute(CapabilityExecutionContext context);
}
