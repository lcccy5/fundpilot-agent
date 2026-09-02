package com.jijing.fund.agent.orchestration;

import java.util.function.Supplier;

/** Carries the local evaluation fixture through the synchronous real Agent tool chain. */
public final class AgentEvaluationFixtureContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    
    /** 执行该 Agent 运行时组件中的 AgentEvaluationFixtureContext 操作。 */
    private AgentEvaluationFixtureContext() { }

    /** Runs one evaluation without leaking fixture state into the next request on a pooled thread. */
    public static <T> T withFixture(String fixtureId, Supplier<T> action) {
        CURRENT.set(fixtureId);
        try { return action.get(); }
        finally { CURRENT.remove(); }
    }

    /** Returns the active fixture identifier or a safe default outside the evaluation endpoint. */
    public static String current() {
        String fixture = CURRENT.get();
        return fixture == null || fixture.isBlank() ? "default" : fixture;
    }
}
