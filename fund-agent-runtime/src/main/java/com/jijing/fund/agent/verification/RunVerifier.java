package com.jijing.fund.agent.verification;

import com.jijing.fund.agent.api.AgentTaskView;
import java.util.ArrayList;
import java.util.List;

/** 实现 RunVerifier 所代表的 Agent 运行时职责。 */
public final class RunVerifier {
    
    /** 在继续处理前校验 verify 对应的输入或状态。 */
    public VerificationReport verify(List<AgentTaskView> tasks){
        List<String> findings=new ArrayList<>();
        List<String> evidence=new ArrayList<>();
        for(AgentTaskView task:tasks){
            if("CANCELLED".equals(task.status())||"WAITING_APPROVAL".equals(task.status()))continue;
            if(!"SUCCEEDED".equals(task.status()))findings.add("incomplete:"+task.taskKey());
            if(task.outputUri()!=null)evidence.add(task.outputUri());
        }
        if(tasks.stream().noneMatch(t->"REPORT_VERIFY".equals(t.capabilityType())||"verify".equals(t.taskKey())))findings.add("missing-verifier");
        return new VerificationReport(findings.isEmpty(),List.copyOf(findings),List.copyOf(evidence));
    }
}
