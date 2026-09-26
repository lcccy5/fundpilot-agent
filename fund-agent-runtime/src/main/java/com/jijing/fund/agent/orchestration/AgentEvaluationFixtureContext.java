package com.jijing.fund.agent.orchestration;

import java.util.function.Supplier;

/**
 * 把本地评估夹具标识放进当前线程，供同步工具链读取。
 * 评估动作抛出的计划、路由、审批或对等代理异常会原样冒出，并在 finally 中清除夹具，避免污染线程池中的下一次请求。
 */
public final class AgentEvaluationFixtureContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /**
     * 阻止实例化。夹具只通过线程局部变量传递，没有可失败的对象状态。
     */
    private AgentEvaluationFixtureContext() {
    }

    /**
     * 在指定夹具下执行一次评估，并保证结束后移除线程状态。
     * 动作抛出的任何异常都会继续向外抛，下一次请求看不到这次夹具。
     */
    public static <T> T withFixture(String fixtureId, Supplier<T> action) {
        CURRENT.set(fixtureId);
        try {
            return action.get();
        } finally {
            CURRENT.remove();
        }
    }

    /**
     * 返回当前夹具标识。
     * 线程上没有夹具或标识为空白时返回 default，评估端点之外的调用不会因此失败。
     */
    public static String current() {
        String fixture = CURRENT.get();
        return fixture == null || fixture.isBlank() ? "default" : fixture;
    }
}
