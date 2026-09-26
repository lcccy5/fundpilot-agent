package com.jijing.fund.agent.execution;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 有界推理执行器：每轮只接受一个白名单动作，连续没有新证据就停止。
 * 步函数返回空观察时会失败；工具预算耗尽时停止且不再调用步函数。
 */
public final class BoundedReactExecutor {
    public static final int MAX_ROUNDS = 5;
    public static final int STOP_AFTER_STALE = 2;

    /**
     * 在预算内执行推理步骤，直到达到轮次、预算、模型停止或证据不再增加。
     * 步函数抛出的异常会直接传出，已经收集的观察不会被包装成结果。
     */
    public Result run(Function<Integer, Observation> step, ExecutionBudget budget) {
        Set<String> seen = new LinkedHashSet<>();
        List<Observation> observations = new ArrayList<>();
        int stale = 0;
        String stop = "MAX_ROUNDS";
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            if (!budget.consumeTools(1)) {
                stop = "BUDGET_TOOLS";
                break;
            }
            Observation obs = step.apply(round);
            observations.add(obs);
            boolean fresh = false;
            List<String> evidenceIds = obs.evidenceIds() == null ? List.<String>of() : obs.evidenceIds();
            for (String id : evidenceIds) {
                if (seen.add(id)) {
                    fresh = true;
                }
            }
            if (!fresh) {
                stale++;
            } else {
                stale = 0;
            }
            if (stale >= STOP_AFTER_STALE) {
                stop = "NO_NEW_EVIDENCE";
                break;
            }
            if ("STOP".equalsIgnoreCase(obs.status())) {
                stop = "MODEL_STOP";
                break;
            }
        }
        return new Result(stop, List.copyOf(observations), List.copyOf(seen));
    }

    /**
     * 有界推理的停止原因、全部观察和去重后的证据标识。
     * 证据标识保持首次出现的顺序；停止原因不会为空。
     */
    public record Result(String stopReason, List<Observation> observations, List<String> evidenceIds) {
    }
}
