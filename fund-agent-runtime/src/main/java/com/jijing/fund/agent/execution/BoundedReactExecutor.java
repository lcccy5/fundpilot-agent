package com.jijing.fund.agent.execution;

import java.util.*;
import java.util.function.Function;

/** Restricted ReAct: one whitelisted action per round; stop when no new evidence. */
public final class BoundedReactExecutor {
    public static final int MAX_ROUNDS=5;
    public static final int STOP_AFTER_STALE=2;
    
    /** 执行 run 操作，并应用相应的 Agent 运行时状态变化。 */
    public Result run(Function<Integer,Observation> step,ExecutionBudget budget){
        Set<String> seen=new LinkedHashSet<>();
        List<Observation> observations=new ArrayList<>();
        int stale=0;
        String stop="MAX_ROUNDS";
        for(int round=1;round<=MAX_ROUNDS;round++){
            if(!budget.consumeTools(1)){stop="BUDGET_TOOLS";break;}
            Observation obs=step.apply(round);
            observations.add(obs);
            boolean fresh=false;
            for(String id:obs.evidenceIds()==null?List.<String>of():obs.evidenceIds())if(seen.add(id))fresh=true;
            if(!fresh)stale++;else stale=0;
            if(stale>=STOP_AFTER_STALE){stop="NO_NEW_EVIDENCE";break;}
            if("STOP".equalsIgnoreCase(obs.status())){stop="MODEL_STOP";break;}
        }
        return new Result(stop,List.copyOf(observations),List.copyOf(seen));
    }
    
    /** 在 Agent 运行时边界间传递 Result 数据的不可变值对象。 */
    public record Result(String stopReason,List<Observation> observations,List<String> evidenceIds){}
}
