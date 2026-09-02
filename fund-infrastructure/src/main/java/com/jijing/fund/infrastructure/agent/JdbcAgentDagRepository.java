package com.jijing.fund.infrastructure.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.*;
import com.jijing.fund.agent.exception.AgentRunNotFoundException;
import com.jijing.fund.agent.planning.PlanDraft;
import com.jijing.fund.agent.planning.PlanTaskDraft;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.routing.RouteDecision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAgentDagRepository implements AgentDagRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public JdbcAgentDagRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    @Override public String createConversation(String ownerUserId,Instant now){
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agent_conversation(conversation_id,owner_user_id,status,message_count,created_at,updated_at) VALUES(?,?,?,?,?,?)",id,ownerUserId,"ACTIVE",0,ts(now),ts(now));
        return id;
    }
    @Override public String startRun(String conversationId,String ownerUserId,String requestId,String executionMode,String routeReason,Instant now){
        String runId=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_run(run_id,conversation_id,request_id,prompt_version,prompt_hash,tool_schema_version,model_provider,model_name,status,execution_mode,router_version,route_reason,started_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,runId,conversationId,requestId==null?"unknown":requestId,"runtime-v4","0".repeat(64),"capabilities-v1","hybrid","hybrid-router","RUNNING",executionMode,"hybrid-router-v1",routeReason,ts(now));
        return runId;
    }
    @Override @Transactional public void saveRoute(String runId,String ownerUserId,RouteDecision decision,Instant now){
        jdbc.update("""
                INSERT INTO agent_route_decision(decision_id,run_id,owner_user_id,execution_mode,direct_variant,router_version,features_json,matched_rule,created_at)
                VALUES(?,?,?,?,?,?,CAST(? AS JSON),?,?)
                """,UUID.randomUUID().toString(),runId,ownerUserId,decision.mode().name(),decision.directVariant()==null?null:decision.directVariant().name(),decision.routerVersion(),json(decision.features()),decision.matchedRule(),ts(now));
        appendEvent(runId,"run.routed","{\"mode\":\""+decision.mode()+"\",\"rule\":\""+decision.matchedRule()+"\"}",now);
    }
    @Override @Transactional public String saveValidatedPlan(String runId,String ownerUserId,PlanDraft draft,Instant now){
        requireOwnedRun(runId,ownerUserId);
        String planId=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_plan(plan_id,run_id,owner_user_id,status,goal,input_snapshot_json,budget_json,schema_version,created_at,updated_at)
                VALUES(?,?,?,?,?,CAST(? AS JSON),CAST(? AS JSON),?,?,?)
                """,planId,runId,ownerUserId,"RUNNING",truncate(draft.goal()==null?"":draft.goal(),500),json(draft.inputSnapshot()),json(draft.budget()),"plan-v1",ts(now),ts(now));
        jdbc.update("INSERT INTO agent_plan_version(plan_id,plan_version,draft_json,validated_at,created_at) VALUES(?,?,CAST(? AS JSON),?,?)",planId,1,json(draft),ts(now),ts(now));
        jdbc.update("UPDATE agent_run SET status='PLAN_RUNNING' WHERE run_id=?",runId);
        appendEvent(runId,"plan.created","{\"planId\":\""+planId+"\"}",now);
        appendEvent(runId,"plan.validated","{\"tasks\":"+(draft.tasks()==null?0:draft.tasks().size())+"}",now);
        for(PlanTaskDraft t:draft.tasks()){
            String input=json(t.input());
            String status=t.dependencies()==null||t.dependencies().isEmpty()?"READY":"PENDING";
            jdbc.update("""
                    INSERT INTO agent_task(task_id,plan_id,plan_version,task_key,capability_type,schema_version,status,input_json,input_hash)
                    VALUES(?,?,?,?,?,?,?,CAST(? AS JSON),?)
                    """,UUID.randomUUID().toString(),planId,1,t.taskKey(),t.taskType(),"capability-v1",status,input,sha(input));
            if(t.dependencies()!=null)for(String dep:t.dependencies())jdbc.update("INSERT INTO agent_task_dependency(plan_id,task_key,depends_on_task_key) VALUES(?,?,?)",planId,t.taskKey(),dep);
            if("READY".equals(status))appendEvent(runId,"task.ready","{\"taskKey\":\""+t.taskKey()+"\"}",now);
        }
        return planId;
    }
    @Override public Optional<AgentRunView> findRun(String runId){
        var rows=jdbc.query("""
                SELECT r.run_id,r.conversation_id,d.owner_user_id,r.status,r.execution_mode,r.route_reason,p.plan_id,r.last_event_sequence
                FROM agent_run r LEFT JOIN agent_route_decision d ON d.run_id=r.run_id LEFT JOIN agent_plan p ON p.run_id=r.run_id
                WHERE r.run_id=?
                """,(rs,n)->view(rs),runId);
        return rows.stream().findFirst();
    }
    @Override public AgentRunView requireOwnedRun(String runId,String ownerUserId){
        var rows=jdbc.query("""
                SELECT r.run_id,r.conversation_id,d.owner_user_id,r.status,r.execution_mode,r.route_reason,p.plan_id,r.last_event_sequence
                FROM agent_run r JOIN agent_route_decision d ON d.run_id=r.run_id LEFT JOIN agent_plan p ON p.run_id=r.run_id
                WHERE r.run_id=? AND d.owner_user_id=?
                """,(rs,n)->view(rs),runId,ownerUserId);
        return rows.stream().findFirst().orElseThrow(()->new AgentRunNotFoundException("run not found"));
    }
    @Override public AgentPlanView requireOwnedPlan(String runId,String ownerUserId){
        AgentRunView run=requireOwnedRun(runId,ownerUserId);
        if(run.planId()==null)throw new AgentRunNotFoundException("plan not found");
        var tasks=jdbc.query("""
                SELECT task_id,task_key,capability_type,status,attempts,output_uri FROM agent_task WHERE plan_id=? ORDER BY task_key
                """,(rs,n)->new AgentTaskView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getString(6)),run.planId());
        return new AgentPlanView(run.planId(),runId,ownerUserId,run.status(),"plan",1,tasks);
    }
    @Override public List<AgentRunEventView> eventsAfter(String runId,String ownerUserId,long lastSequence){
        requireOwnedRun(runId,ownerUserId);
        return jdbc.query("""
                SELECT event_id,sequence,event_type,CAST(payload_json AS CHAR),created_at FROM agent_event
                WHERE run_id=? AND sequence>? ORDER BY sequence
                """,(rs,n)->new AgentRunEventView(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant()),runId,lastSequence);
    }
    @Override @Transactional public void appendEvent(String runId,String type,String payloadJson,Instant now){
        jdbc.update("UPDATE agent_run SET last_event_sequence=last_event_sequence+1 WHERE run_id=?",runId);
        Long seq=jdbc.queryForObject("SELECT last_event_sequence FROM agent_run WHERE run_id=?",Long.class,runId);
        jdbc.update("INSERT INTO agent_event(event_id,run_id,sequence,event_type,payload_json,created_at) VALUES(?,?,?,?,CAST(? AS JSON),?)",
                UUID.randomUUID().toString(),runId,seq==null?1:seq,type,payloadJson==null?"{}":payloadJson,ts(now));
    }
    @Override @Transactional public Optional<ClaimedTask> claimReady(String workerId,Instant now,Duration lease){
        var rows=jdbc.query("""
                SELECT t.task_id,t.plan_id,p.run_id,p.owner_user_id,t.task_key,t.capability_type,CAST(t.input_json AS CHAR),t.input_hash,t.plan_version,t.attempts
                FROM agent_task t
                JOIN agent_plan p ON p.plan_id=t.plan_id
                JOIN agent_run r ON r.run_id=p.run_id
                WHERE t.status IN ('READY','RUNNING') AND r.status NOT IN ('CANCELLED')
                  AND NOT EXISTS (SELECT 1 FROM agent_task_lease l WHERE l.task_id=t.task_id AND l.lease_until>?)
                  AND NOT EXISTS (
                    SELECT 1 FROM agent_task_dependency d
                    JOIN agent_task dep ON dep.plan_id=d.plan_id AND dep.task_key=d.depends_on_task_key
                    WHERE d.plan_id=t.plan_id AND d.task_key=t.task_key AND dep.status<>'SUCCEEDED')
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """,(rs,n)->new ClaimedTask(rs.getString(1),rs.getString(3),rs.getString(2),rs.getInt(9),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getInt(10)+1,rs.getString(4)),ts(now));
        if(rows.isEmpty())return Optional.empty();
        ClaimedTask task=rows.getFirst();
        int updated=jdbc.update("UPDATE agent_task SET status='RUNNING',attempts=attempts+1,started_at=? WHERE task_id=? AND status IN ('READY','RUNNING')",ts(now),task.taskId());
        if(updated==0)return Optional.empty();
        jdbc.update("""
                INSERT INTO agent_task_lease(task_id,owner_instance,lease_until,heartbeat_at,attempt,version)
                VALUES(?,?,?,?,?,1) ON DUPLICATE KEY UPDATE owner_instance=VALUES(owner_instance),lease_until=VALUES(lease_until),heartbeat_at=VALUES(heartbeat_at),attempt=VALUES(attempt),version=version+1
                """,task.taskId(),workerId,ts(now.plus(lease)),ts(now),task.attempt());
        appendEvent(task.runId(),"task.started","{\"taskKey\":\""+task.taskKey()+"\",\"attempt\":"+task.attempt()+"}",now);
        return Optional.of(task);
    }
    @Override @Transactional public void completeTask(String taskId,String executionKey,String outputUri,List<String> evidenceIds,Instant now){
        var rows=jdbc.query("SELECT t.plan_id,p.run_id,t.task_key,t.status,p.owner_user_id,r.status FROM agent_task t JOIN agent_plan p ON p.plan_id=t.plan_id JOIN agent_run r ON r.run_id=p.run_id WHERE t.task_id=?",
                (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)},taskId);
        if(rows.isEmpty())return;
        Object[] row=rows.getFirst();
        String planId=(String)row[0],runId=(String)row[1],taskKey=(String)row[2],status=(String)row[3],owner=(String)row[4],runStatus=(String)row[5];
        if("SUCCEEDED".equals(status))return;
        if("CANCELLED".equals(runStatus)){jdbc.update("UPDATE agent_task SET status='CANCELLED' WHERE task_id=?",taskId);return;}
        jdbc.update("INSERT IGNORE INTO agent_action_idempotency(idempotency_key,owner_user_id,action_type,parameter_hash,result_ref,created_at) VALUES(?,?,?,?,?,?)",
                executionKey,owner,"TASK",executionKey,outputUri,ts(now));
        jdbc.update("UPDATE agent_task SET status='SUCCEEDED',output_uri=?,output_hash=?,evidence_ids_json=CAST(? AS JSON),completed_at=? WHERE task_id=?",
                outputUri,sha(outputUri==null?"":outputUri),json(evidenceIds),ts(now),taskId);
        jdbc.update("DELETE FROM agent_task_lease WHERE task_id=?",taskId);
        appendEvent(runId,"task.completed","{\"taskKey\":\""+taskKey+"\",\"outputUri\":\""+outputUri+"\"}",now);
        if("REPORT_VERIFY".equals(capability(taskId)))appendEvent(runId,"verification.completed","{\"ok\":true}",now);
        if("REPORT_WRITE".equals(capability(taskId)))appendEvent(runId,"report.completed","{\"uri\":\""+outputUri+"\"}",now);
        unlockReady(planId);
        Integer remaining=jdbc.queryForObject("SELECT COUNT(*) FROM agent_task WHERE plan_id=? AND status NOT IN ('SUCCEEDED','CANCELLED','SKIPPED','WAITING_APPROVAL')",Integer.class,planId);
        Integer waiting=jdbc.queryForObject("SELECT COUNT(*) FROM agent_task WHERE plan_id=? AND status='WAITING_APPROVAL'",Integer.class,planId);
        if(remaining!=null&&remaining==0&&(waiting==null||waiting==0)){
            jdbc.update("UPDATE agent_run SET status='SUCCEEDED',completed_at=? WHERE run_id=?",ts(now),runId);
            jdbc.update("UPDATE agent_plan SET status='COMPLETED',updated_at=? WHERE plan_id=?",ts(now),planId);
            appendEvent(runId,"run.completed","{\"runId\":\""+runId+"\"}",now);
        }
    }
    @Override @Transactional public void markWaitingApproval(String taskId,String approvalId,Instant now){
        jdbc.update("UPDATE agent_task SET status='WAITING_APPROVAL' WHERE task_id=?",taskId);
        var run=jdbc.query("SELECT p.run_id,t.task_key FROM agent_task t JOIN agent_plan p ON p.plan_id=t.plan_id WHERE t.task_id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},taskId).stream().findFirst().orElse(null);
        if(run==null)return;
        jdbc.update("UPDATE agent_run SET status='WAITING_APPROVAL' WHERE run_id=?",run[0]);
        appendEvent(run[0],"approval.requested","{\"approvalId\":\""+approvalId+"\",\"taskKey\":\""+run[1]+"\"}",now);
    }
    @Override public void markTaskReady(String taskId){jdbc.update("UPDATE agent_task SET status='READY' WHERE task_id=? AND status NOT IN ('SUCCEEDED','CANCELLED')",taskId);}
    @Override @Transactional public void cancelRun(String runId,String ownerUserId,Instant now){
        requireOwnedRun(runId,ownerUserId);
        jdbc.update("UPDATE agent_run SET status='CANCELLED',completed_at=? WHERE run_id=?",ts(now),runId);
        jdbc.update("UPDATE agent_task t JOIN agent_plan p ON p.plan_id=t.plan_id SET t.status='CANCELLED' WHERE p.run_id=? AND t.status NOT IN ('SUCCEEDED')",runId);
        appendEvent(runId,"run.cancelled","{\"runId\":\""+runId+"\"}",now);
    }
    @Override public String requestApproval(String runId,String taskId,String ownerUserId,String actionType,String parameterHash,String summary,Instant expiresAt,Instant now){
        requireOwnedRun(runId,ownerUserId);
        String id=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_approval(approval_id,run_id,task_id,owner_user_id,action_type,parameter_hash,summary,status,requested_by,expires_at,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,id,runId,taskId,ownerUserId,actionType,parameterHash,summary,"PENDING",ownerUserId,ts(expiresAt),ts(now));
        return id;
    }
    @Override @Transactional public boolean consumeApproval(String approvalId,String ownerUserId,String expectedHash,Instant now){
        int n=jdbc.update("""
                UPDATE agent_approval SET status='APPROVED',approved_by=?,used_at=?
                WHERE approval_id=? AND owner_user_id=? AND status='PENDING' AND used_at IS NULL AND expires_at>=? AND parameter_hash=?
                """,ownerUserId,ts(now),approvalId,ownerUserId,ts(now),expectedHash);
        if(n==0)return false;
        String taskId=jdbc.queryForObject("SELECT task_id FROM agent_approval WHERE approval_id=?",String.class,approvalId);
        String runId=jdbc.queryForObject("SELECT run_id FROM agent_approval WHERE approval_id=?",String.class,approvalId);
        jdbc.update("UPDATE agent_task SET status='READY' WHERE task_id=?",taskId);
        jdbc.update("UPDATE agent_run SET status='PLAN_RUNNING' WHERE run_id=?",runId);
        appendEvent(runId,"approval.resolved","{\"approvalId\":\""+approvalId+"\",\"status\":\"APPROVED\"}",now);
        return true;
    }
    @Override @Transactional public void rejectApproval(String approvalId,String ownerUserId,Instant now){
        int n=jdbc.update("UPDATE agent_approval SET status='REJECTED',used_at=? WHERE approval_id=? AND owner_user_id=?",ts(now),approvalId,ownerUserId);
        if(n==0)throw new AgentRunNotFoundException("approval not found");
        var ids=jdbc.query("SELECT run_id,task_id FROM agent_approval WHERE approval_id=?",(rs,i)->new String[]{rs.getString(1),rs.getString(2)},approvalId).getFirst();
        jdbc.update("UPDATE agent_task SET status='CANCELLED' WHERE task_id=?",ids[1]);
        jdbc.update("UPDATE agent_run SET status='CANCELLED' WHERE run_id=?",ids[0]);
        appendEvent(ids[0],"approval.resolved","{\"approvalId\":\""+approvalId+"\",\"status\":\"REJECTED\"}",now);
    }
    @Override @Transactional public int recoverExpiredLeases(Instant now){
        var expired=jdbc.query("SELECT t.task_id,p.run_id,t.task_key,t.plan_id FROM agent_task t JOIN agent_task_lease l ON l.task_id=t.task_id JOIN agent_plan p ON p.plan_id=t.plan_id WHERE t.status='RUNNING' AND l.lease_until<=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)},ts(now));
        for(String[] row:expired){
            jdbc.update("UPDATE agent_task SET status='READY' WHERE task_id=? AND status='RUNNING'",row[0]);
            jdbc.update("DELETE FROM agent_task_lease WHERE task_id=?",row[0]);
            appendEvent(row[1],"task.retrying","{\"taskKey\":\""+row[2]+"\"}",now);
            unlockReady(row[3]);
        }
        return expired.size();
    }
    @Override public boolean alreadySucceeded(String executionKey){
        Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM agent_action_idempotency WHERE idempotency_key=?",Integer.class,executionKey);
        return n!=null&&n>0;
    }
    @Override @Transactional public void markRunSucceeded(String runId,Instant now){
        jdbc.update("UPDATE agent_run SET status='SUCCEEDED',completed_at=? WHERE run_id=?",ts(now),runId);
        appendEvent(runId,"run.completed","{\"runId\":\""+runId+"\"}",now);
    }
    @Override public boolean isSideEffectAuthorized(String taskId){
        Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE task_id=? AND status='APPROVED' AND used_at IS NOT NULL",Integer.class,taskId);
        return n!=null&&n>0;
    }

    private void unlockReady(String planId){
        var unblock=jdbc.query("""
                SELECT t.task_id FROM agent_task t
                WHERE t.plan_id=? AND t.status='PENDING'
                  AND NOT EXISTS (
                    SELECT 1 FROM agent_task_dependency d
                    JOIN agent_task dep ON dep.plan_id=d.plan_id AND dep.task_key=d.depends_on_task_key
                    WHERE d.plan_id=t.plan_id AND d.task_key=t.task_key AND dep.status<>'SUCCEEDED')
                """,(rs,n)->rs.getString(1),planId);
        for(String readyId:unblock)jdbc.update("UPDATE agent_task SET status='READY' WHERE task_id=? AND status='PENDING'",readyId);
    }
    private String capability(String taskId){return jdbc.queryForObject("SELECT capability_type FROM agent_task WHERE task_id=?",String.class,taskId);}
    private AgentRunView view(java.sql.ResultSet rs)throws java.sql.SQLException{
        return new AgentRunView(rs.getString("run_id"),rs.getString("conversation_id"),rs.getString("owner_user_id"),rs.getString("status"),rs.getString("execution_mode"),rs.getString("route_reason"),rs.getString("plan_id"),rs.getLong("last_event_sequence"));
    }
    private String json(Object value){try{return mapper.writeValueAsString(value==null?Map.of():value);}catch(Exception e){return "{}";}}
    private String sha(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((value==null?"":value).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private Timestamp ts(Instant value){return Timestamp.from(value);}
    private String truncate(String value,int max){return value.length()<=max?value:value.substring(0,max);}
}
