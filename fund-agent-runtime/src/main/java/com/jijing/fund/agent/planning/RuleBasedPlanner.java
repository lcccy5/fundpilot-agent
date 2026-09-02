package com.jijing.fund.agent.planning;

import java.util.*;
import java.time.LocalDate;
import java.time.Clock;
import java.util.regex.Pattern;

/** 实现 RuleBasedPlanner 所代表的 Agent 运行时职责。 */
public final class RuleBasedPlanner {
    private static final Pattern FUND=Pattern.compile("\\d{6}");
    private final Clock clock;
    
    /** 执行该 Agent 运行时组件中的 RuleBasedPlanner 操作。 */
    public RuleBasedPlanner(){this(Clock.systemUTC());}
    
    /** 执行该 Agent 运行时组件中的 RuleBasedPlanner 操作。 */
    public RuleBasedPlanner(Clock clock){this.clock=Objects.requireNonNull(clock,"clock is required");}
    
    /** 创建并初始化当前 Agent 操作所需的 draft 结果。 */
    public PlanDraft draft(String message){
        String text=message==null?"":message;
        List<String> funds=FUND.matcher(text).results().map(m->m.group()).distinct().toList();
        if(text.contains("催化")) return catalystDraft(text,funds);
        if(funds.isEmpty())funds=List.of("000001","110022","161725");
        boolean personal=text.contains("组合")||text.contains("我的");
        boolean export=text.contains("导出")||text.contains("发布")||text.contains("通知");
        LocalDate endDate=LocalDate.now(clock);
        LocalDate startDate=endDate.minusYears(1);
        List<PlanTaskDraft> tasks=new ArrayList<>();
        List<String> metricKeys=new ArrayList<>();
        for(String fund:funds){
            String key="metrics-"+fund;
            metricKeys.add(key);
            tasks.add(new PlanTaskDraft(key,"FUND_METRICS_QUERY",Map.of("fundCode",fund,"startDate",startDate.toString(),"endDate",endDate.toString(),"navBasis","ADJUSTED_NAV"),List.of(),List.of("FUND_METRICS")));
        }
        tasks.add(new PlanTaskDraft("compare","FUND_COMPARE",Map.of("fundCodes",funds,"startDate",startDate.toString(),"endDate",endDate.toString(),"navBasis","ADJUSTED_NAV"),List.copyOf(metricKeys),List.of("FUND_COMPARE")));
        List<String> beforeVerify=new ArrayList<>(List.of("compare"));
        if(personal){
            tasks.add(new PlanTaskDraft("portfolio","PORTFOLIO_SNAPSHOT",Map.of("scope","default"),List.of("compare"),List.of("PORTFOLIO_SNAPSHOT")));
            beforeVerify.add("portfolio");
        }
        tasks.add(new PlanTaskDraft("verify","REPORT_VERIFY",Map.of(),List.copyOf(beforeVerify),List.of("VERIFICATION")));
        tasks.add(new PlanTaskDraft("write","REPORT_WRITE",Map.of(),List.of("verify"),List.of("REPORT")));
        if(export)tasks.add(new PlanTaskDraft("export","REPORT_EXPORT",Map.of("format","markdown"),List.of("write"),List.of("EXPORT")));
        return new PlanDraft(text,Map.of("fundCodes",funds,"startDate",startDate.toString(),"endDate",endDate.toString(),"navBasis","ADJUSTED_NAV"),Map.of("maxTasks",20,"maxToolCalls",20),List.copyOf(tasks));
    }

    /** Builds the single outer task that owns the complete resumable LangGraph4j catalyst workflow. */
    private PlanDraft catalystDraft(String text,List<String> funds){
        Map<String,Object> input=new LinkedHashMap<>();
        if(!funds.isEmpty()) input.put("fundCode",funds.getFirst());
        else input.put("theme",text);
        input.put("lookbackDays",45);
        return new PlanDraft(text,Map.of("fundCodes",funds,"researchType","CATALYST"),Map.of("maxTasks",1,"maxToolCalls",8),
                List.of(new PlanTaskDraft("catalyst-research","CATALYST_RESEARCH",Map.copyOf(input),List.of(),List.of("CATALYST_RESEARCH"))));
    }
}
