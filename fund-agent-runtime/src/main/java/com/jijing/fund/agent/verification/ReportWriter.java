package com.jijing.fund.agent.verification;

import com.jijing.fund.agent.api.AgentTaskView;
import java.util.List;
import java.util.stream.Collectors;

/** 实现 ReportWriter 所代表的 Agent 运行时职责。 */
public final class ReportWriter {
    
    /** 执行该 Agent 运行时组件中的 write 操作。 */
    public String write(VerificationReport report,List<AgentTaskView> tasks){
        if(report==null||!report.passed())throw new IllegalStateException("writer requires a passing verification report");
        String body=tasks.stream().filter(t->"SUCCEEDED".equals(t.status())).map(t->t.taskKey()+"="+t.outputUri()).collect(Collectors.joining("; "));
        return "report v1; claims="+body+"; evidence="+String.join(",",report.evidenceIds());
    }
}
