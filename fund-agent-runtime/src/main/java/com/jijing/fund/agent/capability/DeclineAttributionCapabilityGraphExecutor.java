package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.graph.DurableGraphCheckpointSaver;
import com.jijing.fund.agent.graph.GraphCheckpointStore;
import com.jijing.fund.agent.graph.ResumableDeclineAttributionGraph;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import com.jijing.fund.agent.port.AgentToolCallRecord;
import com.jijing.fund.agent.tool.FundCatalystResearchTool;
import com.jijing.fund.agent.tool.ToolResultStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;

/** Runs decline attribution as a resumable LangGraph task inside the durable DAG. */
public final class DeclineAttributionCapabilityGraphExecutor implements AgentCapabilityExecutor {
    private final FundCatalystResearchTool tool;
    private final AgentDagRepository dag;
    private final GraphCheckpointStore checkpoints;
    private final ObjectMapper mapper;

    public DeclineAttributionCapabilityGraphExecutor(FundCatalystResearchTool tool,AgentDagRepository dag,
                                                      GraphCheckpointStore checkpoints,ObjectMapper mapper){
        this.tool=tool;this.dag=dag;this.checkpoints=checkpoints;this.mapper=mapper;
    }

    @Override public String capabilityType(){return "DECLINE_ATTRIBUTION";}

    @Override public CapabilityExecutionResult execute(CapabilityExecutionContext context){
        context.checkActive();
        var task=context.task();
        Map<String,Object> input=CapabilityJson.input(mapper,task.inputJson());
        String fundCode=optional(input,"fundCode");
        String theme=optional(input,"theme");
        int lookbackDays=integer(input,"lookbackDays",45);
        var request=new ResumableDeclineAttributionGraph.Request(fundCode,theme,lookbackDays);
        var saver=new DurableGraphCheckpointSaver(task.runId(),task.taskId(),ResumableDeclineAttributionGraph.NAME,
                ResumableDeclineAttributionGraph.VERSION,checkpoints,context,dag,mapper);
        boolean resumed=saver.get(org.bsc.langgraph4j.RunnableConfig.builder().threadId(task.taskId()).build()).isPresent();
        context.persist(()->dag.appendEvent(task.runId(),"graph.started",json(Map.of("taskKey",task.taskKey(),"graph",ResumableDeclineAttributionGraph.NAME,"resumed",resumed)),Instant.now()));
        AgentExecutionTrace trace=new AgentExecutionTrace(task.runId(),new TaskTraceRepository(dag,mapper,context),mapper,8,1,Duration.ofSeconds(15));
        var graph=new ResumableDeclineAttributionGraph(
                (graphRequest,attempt)->{context.checkActive();return research(graphRequest,attempt,trace);},
                (draft,evidence)->"下跌原因归因已完成；以下结论仅基于已验证证据 "+String.join(",",evidence)+"。\n"+draft);
        var result=graph.invoke(request,saver,task.taskId());
        Map<String,Object> artifact=Map.of("summary",result.summary(),"evidenceIds",result.evidenceIds(),"attempts",result.attempts(),"graph",ResumableDeclineAttributionGraph.NAME,"graphVersion",ResumableDeclineAttributionGraph.VERSION);
        context.persist(()->dag.appendEvent(task.runId(),"graph.completed",json(Map.of("taskKey",task.taskKey(),"evidenceCount",result.evidenceIds().size(),"attempts",result.attempts())),Instant.now()));
        return new CapabilityExecutionResult(CapabilityJson.artifactUri(mapper,context,artifact),result.evidenceIds());
    }

    private ResumableDeclineAttributionGraph.EvidenceDraft research(ResumableDeclineAttributionGraph.Request request,int attempt,AgentExecutionTrace trace){
        String theme=request.theme()==null?"分析基金近期下跌原因及相关风险事件":request.theme();
        var envelope=tool.research(new FundCatalystResearchTool.Input(theme,request.fundCode(),10,request.lookbackDays()),
                new ToolContext(Map.of(AgentExecutionTrace.TOOL_CONTEXT_KEY,trace)));
        List<String> evidence=envelope.evidence().stream().map(value->value.evidenceId()).toList();
        String draft=envelope.data()==null?envelope.safeErrorMessage():json(envelope.data());
        boolean retryable=envelope.status()==ToolResultStatus.DATA_NOT_READY&&attempt==0;
        if(envelope.status()==ToolResultStatus.USER_CORRECTABLE)throw new IllegalArgumentException(envelope.safeErrorMessage());
        return new ResumableDeclineAttributionGraph.EvidenceDraft(draft,evidence,retryable);
    }

    private static String optional(Map<String,Object> input,String name){Object value=input.get(name);return value==null||String.valueOf(value).isBlank()?null:String.valueOf(value);}
    private static int integer(Map<String,Object> input,String name,int fallback){Object value=input.get(name);if(value==null)return fallback;try{return Integer.parseInt(String.valueOf(value));}catch(NumberFormatException error){throw new IllegalArgumentException(name+" must be an integer",error);}}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException("cannot serialize graph state",error);}}

    /** Adapts tool audit events to the owning DAG task without creating a second run. */
    private static final class TaskTraceRepository implements AgentRuntimeRepository {
        private final AgentDagRepository dag;private final ObjectMapper mapper;private final CapabilityExecutionContext context;
        private TaskTraceRepository(AgentDagRepository dag,ObjectMapper mapper,CapabilityExecutionContext context){this.dag=dag;this.mapper=mapper;this.context=context;}
        @Override public void createConversation(String conversationId,Instant createdAt){throw new UnsupportedOperationException("graph task cannot create chat conversation");}
        @Override public boolean conversationExists(String conversationId){return false;}
        @Override public String startRun(String conversationId,String requestId,String promptVersion,String promptHash,String toolSchemaVersion,String modelProvider,String modelName,Instant startedAt){throw new UnsupportedOperationException("graph task cannot start direct run");}
        @Override public void completeRun(String runId,int modelRounds,int toolCalls,com.jijing.fund.agent.api.TokenUsage usage,long durationMs,Instant completedAt){}
        @Override public void failRun(String runId,String status,String errorCode,String safeMessage,int toolCalls,long durationMs,Instant completedAt){}
        @Override public void recordToolCall(AgentToolCallRecord record){
            try{String payload=mapper.writeValueAsString(Map.of("toolName",record.toolName(),"evidenceIds",record.evidenceIds(),"errorCode",recordDual(record.errorCode())));
                context.persist(()->dag.appendEvent(record.runId(),"graph.tool."+record.resultStatus().toLowerCase(),payload,Instant.now()));}
            catch(Exception error){throw new IllegalStateException("cannot publish graph tool event",error);}
        }
        private static String recordDual(String value){return value==null?"":value;}
    }
}
