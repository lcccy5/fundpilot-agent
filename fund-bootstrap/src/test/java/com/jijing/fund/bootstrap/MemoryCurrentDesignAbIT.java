package com.jijing.fund.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.api.FundAgentRequest;
import com.jijing.fund.agent.api.FundAgentResponse;
import com.jijing.fund.agent.orchestration.AgentEvaluationFixtureContext;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 对比无记忆的独立提问和当前有界记忆设计。默认构建不执行。
 */
@SpringBootTest(properties = {
        "fund.agent.enabled=true",
        "fund.knowledge.enabled=true",
        "fund.provider.type=mock",
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles({"local", "test", "agent-eval"})
@Import(MemoryRealModelAbIT.TokenObservationConfiguration.class)
@EnabledIfEnvironmentVariable(named="RUN_CURRENT_MEMORY_AB_EVAL",matches="true")
class MemoryCurrentDesignAbIT {
    @Autowired com.jijing.fund.agent.api.FundAgentUseCase agent;
    @Autowired AgentRuntimeRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MemoryRealModelAbIT.ModelTokenAccumulator tokenAccumulator;

    /**
     * 对同一组任务分别跑无记忆和当前记忆，并写出对比报告。
     */
    @Test void comparesNoMemoryWithCurrentQueryAwareMemory() throws Exception {
        List<Observation> baseline=new ArrayList<>();
        List<Observation> memory=new ArrayList<>();
        List<TaskGroup> groups=taskGroups();
        for(int groupIndex=0;groupIndex<groups.size();groupIndex++){
            TaskGroup group=groups.get(groupIndex);
            String memoryConversation=createConversation();
            Set<String> baselineSignatures=new HashSet<>();
            Set<String> memorySignatures=new HashSet<>();
            for(int turnIndex=0;turnIndex<group.turns().size();turnIndex++){
                Turn turn=group.turns().get(turnIndex);
                String fixture="current-memory-g"+groupIndex+"-t"+turnIndex;
                baseline.add(execute("A",group,turnIndex,createConversation(),turn.standalone(),fixture,turn,baselineSignatures));
                memory.add(execute("B",group,turnIndex,memoryConversation,turn.contextual(),fixture,turn,memorySignatures));
            }
        }

        String modelName=jdbc.queryForObject("select model_name from agent_run where request_id like 'current-memory-ab-%' order by started_at desc limit 1",String.class);
        Report report=report(modelName,groups,baseline,memory);
        Path target=Path.of("target","memory-current-design-ab-report.json");
        Files.createDirectories(target.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(),report);
        System.out.printf(Locale.ROOT,
                "CURRENT_MEMORY_AB turns=%d promptChange=%.2f%% totalChange=%.2f%% followUpTotalChange=%.2f%% toolReduction=%.2f%% effectiveFactHit=%.2f%% qualityA=%.2f%% qualityB=%.2f%%%n",
                report.turnsPerGroup(),report.promptTokenChangePercent(),report.totalTokenChangePercent(),
                report.followUps().totalTokenChangePercent(),report.toolCallReductionPercent(),report.effectiveFactHitRatePercent(),
                report.baseline().qualityPassRatePercent(),report.memory().qualityPassRatePercent());
        assertThat(baseline).hasSize(16);
        assertThat(memory).hasSize(16);
        assertThat(report.baseline().modelCalls()).isGreaterThanOrEqualTo(16);
        assertThat(report.memory().modelCalls()).isGreaterThanOrEqualTo(16);
    }

    /**
     * 执行一轮提问，统计令牌、重复工具和事实卡是否真正替代了工具。
     */
    private Observation execute(String arm,TaskGroup group,int turnIndex,String conversationId,String question,
                                String fixture,Turn turn,Set<String> priorSignatures){
        String requestId="current-memory-ab-"+arm+"-"+group.id()+"-"+turnIndex+"-"+UUID.randomUUID();
        FundAgentResponse response=null;RuntimeException failure=null;String runId;
        try{response=AgentEvaluationFixtureContext.withFixture(fixture,()->agent.chat(new FundAgentRequest(conversationId,question,requestId)));runId=response.runId();}
        catch(RuntimeException ex){failure=ex;runId=jdbc.queryForObject("select run_id from agent_run where request_id=? order by started_at desc limit 1",String.class,requestId);}
        MemoryRealModelAbIT.TokenTotals tokens=tokenAccumulator.remove(runId);
        List<ToolCall> calls=jdbc.query("select tool_name,argument_hash from agent_tool_call where run_id=? order by id",
                (rs,row)->new ToolCall(rs.getString(1),rs.getString(2)),runId);
        Set<String> usedCards=new LinkedHashSet<>(jdbc.query("select u.card_id from agent_fact_card_usage u where u.run_id=?",(rs,row)->rs.getString(1),runId));
        int repeated=0;for(ToolCall call:calls)if(!priorSignatures.add(call.name()+"|"+call.argumentHash()))repeated++;
        boolean expectedEvidence=response!=null&&response.evidence().stream().map(EvidenceReference::evidenceType).anyMatch(turn.expectedEvidence()::equals);
        boolean quality=response!=null&&response.answer()!=null&&!response.answer().isBlank()&&expectedEvidence;
        boolean calledExpected=calls.stream().anyMatch(call->call.name().equals(turn.expectedTool()));
        boolean effectiveHit=turn.followUp()&&!usedCards.isEmpty()&&!calledExpected&&quality;
        return new Observation(arm,group.id(),group.kind(),turnIndex,turn.followUp(),runId,question,
                response==null?null:response.answer(),failure==null?null:failure.getClass().getSimpleName()+": "+failure.getMessage(),
                tokens,calls,repeated,Set.copyOf(usedCards),effectiveHit,quality);
    }

    /**
     * 新建一条空对话供本轮使用。
     */
    private String createConversation(){String id=UUID.randomUUID().toString();repository.createConversation(id,Instant.now());return id;}

    /**
     * 汇总两侧的令牌、工具次数和质量，并单独比较单轮、链首和追问。
     */
    private static Report report(String modelName,List<TaskGroup> groups,List<Observation> baseline,List<Observation> memory){
        Metrics a=metrics(baseline),b=metrics(memory);
        Comparison singles=comparison(baseline.stream().filter(item->item.kind()==TaskKind.SINGLE).toList(),memory.stream().filter(item->item.kind()==TaskKind.SINGLE).toList());
        Comparison chainInitials=comparison(baseline.stream().filter(item->item.kind()==TaskKind.CHAIN&&!item.followUp()).toList(),memory.stream().filter(item->item.kind()==TaskKind.CHAIN&&!item.followUp()).toList());
        Comparison followUps=comparison(baseline.stream().filter(Observation::followUp).toList(),memory.stream().filter(Observation::followUp).toList());
        long opportunities=memory.stream().filter(Observation::followUp).count();
        long hits=memory.stream().filter(Observation::effectiveFactHit).count();
        return new Report("memory-current-design-ab-v1",modelName,groups.size(),baseline.size(),a,b,
                change(a.promptTokens(),b.promptTokens()),change(a.totalTokens(),b.totalTokens()),
                reduction(a.toolCalls(),b.toolCalls()),percent(hits,opportunities),singles,chainInitials,followUps,
                "A uses a fresh conversation and a self-contained question for every turn. B uses one conversation per 1-3 turn user task and natural follow-ups with the current two-turn window, conversation note, TTL and query-aware per-fund memory.");
    }

    /**
     * 计算两组观察之间的令牌变化和工具减少比例。
     */
    private static Comparison comparison(List<Observation> a,List<Observation> b){Metrics left=metrics(a),right=metrics(b);return new Comparison(left,right,change(left.promptTokens(),right.promptTokens()),change(left.totalTokens(),right.totalTokens()),reduction(left.toolCalls(),right.toolCalls()));}
    /**
     * 把一组观察加成模型调用、令牌、工具和质量通过率。
     */
    private static Metrics metrics(List<Observation> values){long prompt=values.stream().map(Observation::tokens).mapToLong(MemoryRealModelAbIT.TokenTotals::promptTokens).sum();long completion=values.stream().map(Observation::tokens).mapToLong(MemoryRealModelAbIT.TokenTotals::completionTokens).sum();return new Metrics(values.size(),values.stream().map(Observation::tokens).mapToLong(MemoryRealModelAbIT.TokenTotals::modelCalls).sum(),prompt,completion,prompt+completion,values.stream().mapToLong(value->value.calls().size()).sum(),values.stream().mapToLong(Observation::repeatedCalls).sum(),percent(values.stream().filter(Observation::quality).count(),values.size()));}
    /**
     * 计算从基线到对照的百分比变化。基线为零时记为零。
     */
    private static double change(long a,long b){return a==0?0:round(100D*(b-a)/a);}
    /**
     * 计算对照相对基线减少的百分比。
     */
    private static double reduction(long a,long b){return a==0?0:round(100D*(a-b)/a);}
    /**
     * 计算分子占分母的百分比。分母为零时记为零。
     */
    private static double percent(long a,long b){return b==0?0:round(100D*a/b);}
    /**
     * 保留两位小数，便于报告比较。
     */
    private static double round(double value){return Math.round(value*100D)/100D;}

    /**
     * 提供单轮和多轮追问任务，覆盖资料、指标、净值、行情、文档和对比。
     */
    private static List<TaskGroup> taskGroups(){return List.of(
            new TaskGroup("single-profile",TaskKind.SINGLE,List.of(new Turn("查询基金000001的名称、类型、基金公司和基金经理，并引用工具证据。","查询基金000001的名称、类型、基金公司和基金经理，并引用工具证据。","get_fund_profile","FUND_PROFILE",false))),
            new TaskGroup("metrics-chain",TaskKind.CHAIN,List.of(
                    new Turn("查询基金000001在2026年1月1日至2026年8月31日的收益、最大回撤和年化波动率，并引用工具证据。","查询基金000001在2026年1月1日至2026年8月31日的收益、最大回撤和年化波动率，并引用工具证据。","calculate_fund_metrics","FUND_METRICS",false),
                    new Turn("基金000001在2026年1月1日至2026年8月31日的最大回撤是多少？请引用工具证据。","同一时间段内，它的最大回撤是多少？请引用已有证据。","calculate_fund_metrics","FUND_METRICS",true),
                    new Turn("基金000001在2026年1月1日至2026年8月31日的年化波动率是多少？请引用工具证据。","它同期的年化波动率呢？请引用已有证据。","calculate_fund_metrics","FUND_METRICS",true))),
            new TaskGroup("nav-chain",TaskKind.CHAIN,List.of(
                    new Turn("查询基金000002在2026年7月1日至2026年8月31日的历史净值，并引用工具证据。","查询基金000002在2026年7月1日至2026年8月31日的历史净值，并引用工具证据。","get_fund_nav_history","FUND_NAV",false),
                    new Turn("基金000002在2026年8月31日的期末累计净值是多少？请引用工具证据。","刚才这只基金的期末累计净值是多少？请引用已有证据。","get_fund_nav_history","FUND_NAV",true))),
            new TaskGroup("profile-chain",TaskKind.CHAIN,List.of(
                    new Turn("查询基金000003的基本资料并引用工具证据。","查询基金000003的基本资料并引用工具证据。","get_fund_profile","FUND_PROFILE",false),
                    new Turn("基金000003由哪家基金公司管理？请引用工具证据。","它由哪家基金公司管理？请引用已有证据。","get_fund_profile","FUND_PROFILE",true),
                    new Turn("基金000003的基金经理是谁？请引用工具证据。","那基金经理是谁？请引用已有证据。","get_fund_profile","FUND_PROFILE",true))),
            new TaskGroup("single-realtime",TaskKind.SINGLE,List.of(new Turn("查询基金000004当前的ETF代理实时行情和涨跌幅，并引用工具证据。","查询基金000004当前的ETF代理实时行情和涨跌幅，并引用工具证据。","fund_realtime_quote","UNDERLYING_ETF_PROXY",false))),
            new TaskGroup("realtime-chain",TaskKind.CHAIN,List.of(
                    new Turn("查询基金000005当前的ETF代理实时行情，并引用工具证据。","查询基金000005当前的ETF代理实时行情，并引用工具证据。","fund_realtime_quote","UNDERLYING_ETF_PROXY",false),
                    new Turn("基金000005当前ETF代理行情的涨跌幅是多少？请引用工具证据。","它刚才的涨跌幅是多少？请引用已有证据。","fund_realtime_quote","UNDERLYING_ETF_PROXY",true))),
            new TaskGroup("document-chain",TaskKind.CHAIN,List.of(
                    new Turn("查询基金000006最新季报中的投资策略，并引用文档证据。","查询基金000006最新季报中的投资策略，并引用文档证据。","search_fund_documents","FUND_DOCUMENT",false),
                    new Turn("基金000006最新季报中的投资策略是什么？请引用文档证据。","刚才季报里提到的投资策略是什么？请引用已有文档证据。","search_fund_documents","FUND_DOCUMENT",true))),
            new TaskGroup("comparison-chain",TaskKind.CHAIN,List.of(
                    new Turn("比较基金000001和110022在2026年1月1日至2026年8月31日的收益和最大回撤，并引用工具证据。","比较基金000001和110022在2026年1月1日至2026年8月31日的收益和最大回撤，并引用工具证据。","compare_fund_metrics","FUND_COMPARISON",false),
                    new Turn("基金000001和110022在2026年1月1日至2026年8月31日谁的最大回撤更小？请引用工具证据。","这两只基金同期谁的最大回撤更小？请引用已有证据。","compare_fund_metrics","FUND_COMPARISON",true))));}

    /**
     * 区分一次性问题和需要追问的任务链。
     */
    enum TaskKind{SINGLE,CHAIN}
    /**
     * 一组共享对话的提问。
     */
    record TaskGroup(String id,TaskKind kind,List<Turn> turns){}
    /**
     * 同一问法的独立表述、上下文表述，以及期望的工具和证据。
     */
    record Turn(String standalone,String contextual,String expectedTool,String expectedEvidence,boolean followUp){}
    /**
     * 一次工具调用的名称和参数摘要。
     */
    record ToolCall(String name,String argumentHash){}
    /**
     * 一轮对比的用量、质量和事实卡命中情况。
     */
    record Observation(String arm,String taskGroup,TaskKind kind,int turnIndex,boolean followUp,String runId,String question,String answer,String error,MemoryRealModelAbIT.TokenTotals tokens,List<ToolCall> calls,int repeatedCalls,Set<String> usedCardIds,boolean effectiveFactHit,boolean quality){}
    /**
     * 一组观察的合计用量和质量通过率。
     */
    record Metrics(int turns,long modelCalls,long promptTokens,long completionTokens,long totalTokens,long toolCalls,long repeatedToolCalls,double qualityPassRatePercent){}
    /**
     * 两侧合计以及相对变化。
     */
    record Comparison(Metrics baseline,Metrics memory,double promptTokenChangePercent,double totalTokenChangePercent,double toolCallReductionPercent){}
    /**
     * 当前记忆设计对比实验的完整结果和方法说明。
     */
    record Report(String datasetVersion,String modelName,int taskGroups,int turnsPerGroup,Metrics baseline,Metrics memory,double promptTokenChangePercent,double totalTokenChangePercent,double toolCallReductionPercent,double effectiveFactHitRatePercent,Comparison singles,Comparison chainInitials,Comparison followUps,String methodology){}
}
