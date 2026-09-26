package com.jijing.fund.agent.capability;

import java.util.List;

/**
 * 仅供单元测试使用的通配执行器，在没有 Spring Bean 时给任务工作者一个可成功的实现。
 * 不读取真实数据；任务键为空时仍会生成带有“null”片段的地址。
 */
final class LegacyTestCapabilityExecutor implements AgentCapabilityExecutor {

    /**
     * 返回通配类型，让注册表在没有精确匹配时选中它。
     * 不会失败。
     */
    @Override
    public String capabilityType() {
        return "*";
    }

    /**
     * 用计划标识和任务键拼出一个伪产物地址，并附带一条伪证据。
     * 上下文或任务为空时失败；不访问外部系统。
     */
    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        String taskKey = context.task().taskKey();
        return new CapabilityExecutionResult(
                "artifact://" + context.task().planId() + "/" + taskKey,
                List.of("ev-" + taskKey));
    }
}
