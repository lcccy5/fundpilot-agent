package com.jijing.fund.infrastructure.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.AgentPlanView;
import com.jijing.fund.agent.api.AgentRunEventView;
import com.jijing.fund.agent.api.AgentRunView;
import com.jijing.fund.agent.api.AgentTaskView;
import com.jijing.fund.agent.exception.AgentRunNotFoundException;
import com.jijing.fund.agent.exception.TaskLeaseLostException;
import com.jijing.fund.agent.planning.PlanDraft;
import com.jijing.fund.agent.planning.PlanTaskDraft;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.routing.RouteDecision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计划、任务、租约和审批的 JDBC 存储。
 * 领取和续约会把调用方时钟换成数据库时钟，避免工人时钟回拨把过期租约救活。
 * 完成、失败和等待审批都会锁住当前租约；租约丢失抛出 {@link TaskLeaseLostException}。
 * 幂等键已存在时 {@link #alreadySucceeded} 为 true。完成任务时用 {@code INSERT IGNORE} 写幂等行，重复完成不会覆盖结果引用。
 * 没有 HTTP 超时。数据库连接失败由 Spring 抛出。
 */
@Repository
public class JdbcAgentDagRepository implements AgentDagRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 不在构造时领取任务。 */
    public JdbcAgentDagRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 新建 ACTIVE 会话，消息数为 0。 */
    @Override
    public String createConversation(String ownerUserId, Instant now) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_conversation(conversation_id,owner_user_id,status,message_count,created_at,updated_at)
                VALUES(?,?,?,?,?,?)
                """, id, ownerUserId, "ACTIVE", 0, ts(now), ts(now));
        return id;
    }

    /** 请求编号为空时写成 unknown。运行以 RUNNING 开始，提示词版本固定为 runtime-v4。 */
    @Override
    public String startRun(String conversationId, String ownerUserId, String requestId, String executionMode,
            String routeReason, Instant now) {
        String runId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_run(run_id,conversation_id,request_id,prompt_version,prompt_hash,tool_schema_version,model_provider,model_name,status,execution_mode,router_version,route_reason,started_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, runId, conversationId, requestId == null ? "unknown" : requestId, "runtime-v4", "0".repeat(64),
                "capabilities-v1", "hybrid", "hybrid-router", "RUNNING", executionMode, "hybrid-router-v1",
                routeReason, ts(now));
        return runId;
    }

    /** 路由决策和运行上的模式一起写，并追加一条 routed 事件。 */
    @Override
    @Transactional
    public void saveRoute(String runId, String ownerUserId, RouteDecision decision, Instant now) {
        jdbc.update("""
                INSERT INTO agent_route_decision(decision_id,run_id,owner_user_id,execution_mode,direct_variant,router_version,features_json,matched_rule,model_suggestion,override_reason,created_at)
                VALUES(?,?,?,?,?,?,CAST(? AS JSON),?,?,?,?)
                """, UUID.randomUUID().toString(), runId, ownerUserId, decision.mode().name(),
                decision.directVariant() == null ? null : decision.directVariant().name(), decision.routerVersion(),
                json(decision.features()), decision.matchedRule(), truncate(decision.modelSuggestion(), 32),
                truncate(decision.overrideReason(), 128), ts(now));
        jdbc.update("UPDATE agent_run SET execution_mode=?,router_version=?,route_reason=? WHERE run_id=?",
                decision.mode().name(), decision.routerVersion(), decision.matchedRule(), runId);
        appendEvent(runId, "run.routed",
                "{\"mode\":\"" + decision.mode() + "\",\"rule\":\"" + decision.matchedRule() + "\"}", now);
    }

    /**
     * 校验后的计划写成版本 1。没有依赖的任务直接 READY，其余 PENDING。
     * 运行不存在或不属于该用户时由 {@link #requireOwnedRun} 拒绝。
     */
    @Override
    @Transactional
    public String saveValidatedPlan(String runId, String ownerUserId, PlanDraft draft, Instant now) {
        requireOwnedRun(runId, ownerUserId);
        String planId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_plan(plan_id,run_id,owner_user_id,status,goal,input_snapshot_json,budget_json,schema_version,created_at,updated_at)
                VALUES(?,?,?,?,?,CAST(? AS JSON),CAST(? AS JSON),?,?,?)
                """, planId, runId, ownerUserId, "RUNNING", truncate(draft.goal() == null ? "" : draft.goal(), 500),
                json(draft.inputSnapshot()), json(draft.budget()), "plan-v1", ts(now), ts(now));
        jdbc.update(
                "INSERT INTO agent_plan_version(plan_id,plan_version,draft_json,validated_at,created_at) VALUES(?,?,CAST(? AS JSON),?,?)",
                planId, 1, json(draft), ts(now), ts(now));
        jdbc.update("UPDATE agent_run SET status='PLAN_RUNNING' WHERE run_id=?", runId);
        appendEvent(runId, "plan.created", "{\"planId\":\"" + planId + "\"}", now);
        int taskCount = draft.tasks() == null ? 0 : draft.tasks().size();
        appendEvent(runId, "plan.validated", "{\"tasks\":" + taskCount + "}", now);
        for (PlanTaskDraft task : draft.tasks()) {
            String input = json(task.input());
            String status = task.dependencies() == null || task.dependencies().isEmpty() ? "READY" : "PENDING";
            jdbc.update("""
                    INSERT INTO agent_task(task_id,plan_id,plan_version,task_key,capability_type,schema_version,status,input_json,input_hash)
                    VALUES(?,?,?,?,?,?,?,CAST(? AS JSON),?)
                    """, UUID.randomUUID().toString(), planId, 1, task.taskKey(), task.taskType(), "capability-v1",
                    status, input, sha(input));
            if (task.dependencies() != null) {
                for (String dependency : task.dependencies()) {
                    jdbc.update(
                            "INSERT INTO agent_task_dependency(plan_id,task_key,depends_on_task_key) VALUES(?,?,?)",
                            planId, task.taskKey(), dependency);
                }
            }
            if ("READY".equals(status)) {
                appendEvent(runId, "task.ready", "{\"taskKey\":\"" + task.taskKey() + "\"}", now);
            }
        }
        return planId;
    }

    /** 运行不存在时为空。路由或计划缺失时对应字段为空。 */
    @Override
    public Optional<AgentRunView> findRun(String runId) {
        List<AgentRunView> rows = jdbc.query("""
                SELECT r.run_id,r.conversation_id,d.owner_user_id,r.status,r.execution_mode,r.route_reason,p.plan_id,r.last_event_sequence
                FROM agent_run r LEFT JOIN agent_route_decision d ON d.run_id=r.run_id LEFT JOIN agent_plan p ON p.run_id=r.run_id
                WHERE r.run_id=?
                """, (rs, n) -> view(rs), runId);
        return rows.stream().findFirst();
    }

    /** 必须能连上该用户的路由决策，否则当作运行不存在。 */
    @Override
    public AgentRunView requireOwnedRun(String runId, String ownerUserId) {
        List<AgentRunView> rows = jdbc.query("""
                SELECT r.run_id,r.conversation_id,d.owner_user_id,r.status,r.execution_mode,r.route_reason,p.plan_id,r.last_event_sequence
                FROM agent_run r JOIN agent_route_decision d ON d.run_id=r.run_id LEFT JOIN agent_plan p ON p.run_id=r.run_id
                WHERE r.run_id=? AND d.owner_user_id=?
                """, (rs, n) -> view(rs), runId, ownerUserId);
        return rows.stream().findFirst().orElseThrow(() -> new AgentRunNotFoundException("run not found"));
    }

    /** 运行还没有计划时抛出未找到。任务按任务键排序。 */
    @Override
    public AgentPlanView requireOwnedPlan(String runId, String ownerUserId) {
        AgentRunView run = requireOwnedRun(runId, ownerUserId);
        if (run.planId() == null) {
            throw new AgentRunNotFoundException("plan not found");
        }
        List<AgentTaskView> tasks = jdbc.query("""
                SELECT task_id,task_key,capability_type,status,attempts,output_uri FROM agent_task
                WHERE plan_id=? ORDER BY task_key
                """, (rs, n) -> new AgentTaskView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getString(6)), run.planId());
        return new AgentPlanView(run.planId(), runId, ownerUserId, run.status(), "plan", 1, tasks);
    }

    /** 先确认所有权，再返回序号大于给定值的事件。 */
    @Override
    public List<AgentRunEventView> eventsAfter(String runId, String ownerUserId, long lastSequence) {
        requireOwnedRun(runId, ownerUserId);
        return jdbc.query("""
                SELECT event_id,sequence,event_type,CAST(payload_json AS CHAR),created_at FROM agent_event
                WHERE run_id=? AND sequence>? ORDER BY sequence
                """, (rs, n) -> new AgentRunEventView(rs.getString(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getTimestamp(5).toInstant()), runId, lastSequence);
    }

    /** 序号在运行行上自增后再插入。查询不到序号时用 1。空载荷写成 {}。 */
    @Override
    @Transactional
    public void appendEvent(String runId, String type, String payloadJson, Instant now) {
        jdbc.update("UPDATE agent_run SET last_event_sequence=last_event_sequence+1 WHERE run_id=?", runId);
        Long sequence = jdbc.queryForObject("SELECT last_event_sequence FROM agent_run WHERE run_id=?", Long.class,
                runId);
        jdbc.update(
                "INSERT INTO agent_event(event_id,run_id,sequence,event_type,payload_json,created_at) VALUES(?,?,?,?,CAST(? AS JSON),?)",
                UUID.randomUUID().toString(), runId, sequence == null ? 1 : sequence, type,
                payloadJson == null ? "{}" : payloadJson, ts(now));
    }

    /**
     * 原子领取一条就绪或仍在跑但租约已过的任务，并递增围栏版本。
     * 租约短于 3 毫秒直接拒绝。没有可领任务，或状态在锁定后已变，返回空。
     * 查询条件里的“现在”再读一次数据库时钟，和随后写入使用的时刻可以相差一次往返。
     */
    @Override
    @Transactional
    public Optional<ClaimedTask> claimReady(String workerId, Instant now, Duration lease) {
        if (lease.toMillis() < 3) {
            throw new IllegalArgumentException("lease must be at least 3ms");
        }
        now = databaseNow();
        List<ClaimedTask> rows = jdbc.query("""
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
                """, (rs, n) -> new ClaimedTask(rs.getString(1), rs.getString(3), rs.getString(2), rs.getInt(9),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(10) + 1,
                rs.getString(4)), ts(databaseNow()));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ClaimedTask task = rows.getFirst();
        if ("RUNNING".equals(jdbc.queryForObject("SELECT status FROM agent_task WHERE task_id=?", String.class,
                task.taskId()))) {
            appendEvent(task.runId(), "task.retrying", json(Map.of("taskKey", task.taskKey())), now);
        }
        int updated = jdbc.update(
                "UPDATE agent_task SET status='RUNNING',attempts=attempts+1,started_at=? WHERE task_id=? AND status IN ('READY','RUNNING')",
                ts(now), task.taskId());
        if (updated == 0) {
            return Optional.empty();
        }
        jdbc.update("""
                INSERT INTO agent_task_lease(task_id,owner_instance,lease_until,heartbeat_at,attempt,version)
                VALUES(?,?,?,?,?,1) ON DUPLICATE KEY UPDATE owner_instance=VALUES(owner_instance),lease_until=VALUES(lease_until),heartbeat_at=VALUES(heartbeat_at),attempt=VALUES(attempt),version=version+1
                """, task.taskId(), workerId, ts(now.plus(lease)), ts(now), task.attempt());
        appendEvent(task.runId(), "task.started",
                "{\"taskKey\":\"" + task.taskKey() + "\",\"attempt\":" + task.attempt() + "}", now);
        long version = jdbc.queryForObject("SELECT version FROM agent_task_lease WHERE task_id=?", Long.class,
                task.taskId());
        return Optional.of(new ClaimedTask(task.taskId(), task.runId(), task.planId(), task.planVersion(),
                task.taskKey(), task.capabilityType(), task.inputJson(), task.inputHash(), task.attempt(),
                task.ownerUserId(), workerId, version));
    }

    /**
     * 只有仍持有当前租约才提交输出、幂等行和事件。
     * 任务已成功则直接返回。运行已取消则把任务改成取消。
     * 租约行保留，避免以后的领取复用同一围栏版本。
     */
    @Override
    @Transactional
    public void completeTask(ClaimedTask claim, String executionKey, String outputUri, List<String> evidenceIds,
            Instant now) {
        String taskId = claim.taskId();
        lockClaim(claim);
        List<Object[]> rows = jdbc.query("""
                SELECT t.plan_id,p.run_id,t.task_key,t.status,p.owner_user_id,r.status
                FROM agent_task t JOIN agent_plan p ON p.plan_id=t.plan_id JOIN agent_run r ON r.run_id=p.run_id
                WHERE t.task_id=?
                """, (rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6)}, taskId);
        if (rows.isEmpty()) {
            return;
        }
        Object[] row = rows.getFirst();
        String planId = (String) row[0];
        String runId = (String) row[1];
        String taskKey = (String) row[2];
        String status = (String) row[3];
        String owner = (String) row[4];
        String runStatus = (String) row[5];
        if ("SUCCEEDED".equals(status)) {
            return;
        }
        if ("CANCELLED".equals(runStatus)) {
            jdbc.update("UPDATE agent_task SET status='CANCELLED' WHERE task_id=?", taskId);
            return;
        }
        jdbc.update("""
                INSERT IGNORE INTO agent_action_idempotency(idempotency_key,owner_user_id,action_type,parameter_hash,result_ref,created_at)
                VALUES(?,?,?,?,?,?)
                """, executionKey, owner, "TASK", executionKey, outputUri, ts(now));
        jdbc.update("""
                UPDATE agent_task SET status='SUCCEEDED',output_uri=?,output_hash=?,evidence_ids_json=CAST(? AS JSON),completed_at=?
                WHERE task_id=?
                """, outputUri, sha(outputUri == null ? "" : outputUri), json(evidenceIds), ts(now), taskId);
        appendEvent(runId, "task.completed",
                "{\"taskKey\":\"" + taskKey + "\",\"outputUri\":\"" + outputUri + "\"}", now);
        if ("REPORT_VERIFY".equals(capability(taskId))) {
            appendEvent(runId, "verification.completed", "{\"ok\":true}", now);
        }
        if ("REPORT_WRITE".equals(capability(taskId))) {
            appendEvent(runId, "report.completed", "{\"uri\":\"" + outputUri + "\"}", now);
        }
        unlockReady(planId);
        Integer remaining = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_task
                WHERE plan_id=? AND status NOT IN ('SUCCEEDED','CANCELLED','SKIPPED','WAITING_APPROVAL')
                """, Integer.class, planId);
        Integer waiting = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task WHERE plan_id=? AND status='WAITING_APPROVAL'", Integer.class,
                planId);
        if (remaining != null && remaining == 0 && (waiting == null || waiting == 0)) {
            jdbc.update("UPDATE agent_run SET status='SUCCEEDED',completed_at=? WHERE run_id=?", ts(now), runId);
            jdbc.update("UPDATE agent_plan SET status='COMPLETED',updated_at=? WHERE plan_id=?", ts(now), planId);
            appendEvent(runId, "run.completed", "{\"runId\":\"" + runId + "\"}", now);
        }
    }

    /**
     * 记录终态失败并取消同计划里尚未结束的其他任务，避免缺输入的报告继续写。
     * 原因为空时用“任务执行失败”。运行已取消则不再改状态。租约行保留。
     */
    @Override
    @Transactional
    public void failTask(ClaimedTask claim, String reason, Instant now) {
        String taskId = claim.taskId();
        lockClaim(claim);
        List<String[]> rows = jdbc.query("""
                SELECT t.plan_id,p.run_id,t.task_key,r.status
                FROM agent_task t JOIN agent_plan p ON p.plan_id=t.plan_id JOIN agent_run r ON r.run_id=p.run_id
                WHERE t.task_id=?
                """, (rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                taskId);
        if (rows.isEmpty()) {
            return;
        }
        String[] row = rows.getFirst();
        if ("CANCELLED".equals(row[3])) {
            return;
        }
        String detail = truncate(reason == null || reason.isBlank() ? "任务执行失败" : reason, 500);
        jdbc.update(
                "UPDATE agent_task SET status='FAILED',completed_at=? WHERE task_id=? AND status NOT IN ('SUCCEEDED','CANCELLED')",
                ts(now), taskId);
        jdbc.update("""
                UPDATE agent_task SET status='CANCELLED'
                WHERE plan_id=? AND task_id<>? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')
                """, row[0], taskId);
        jdbc.update("UPDATE agent_plan SET status='FAILED',updated_at=? WHERE plan_id=?", ts(now), row[0]);
        jdbc.update("UPDATE agent_run SET status='FAILED',completed_at=? WHERE run_id=?", ts(now), row[1]);
        appendEvent(row[1], "task.failed", json(Map.of("taskKey", row[2], "reason", detail)), now);
        appendEvent(row[1], "run.failed", json(Map.of("runId", row[1], "reason", detail)), now);
    }

    /**
     * 把任务和运行标成等待审批，并把租约截止改成数据库当前时间，审批通过后可以立刻再领。
     * 围栏版本不重置。
     */
    @Override
    @Transactional
    public void markWaitingApproval(ClaimedTask claim, String approvalId, Instant now) {
        String taskId = claim.taskId();
        lockClaim(claim);
        jdbc.update("UPDATE agent_task SET status='WAITING_APPROVAL' WHERE task_id=?", taskId);
        jdbc.update("UPDATE agent_task_lease SET lease_until=? WHERE task_id=?", ts(databaseNow()), taskId);
        String[] run = jdbc.query(
                "SELECT p.run_id,t.task_key FROM agent_task t JOIN agent_plan p ON p.plan_id=t.plan_id WHERE t.task_id=?",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, taskId).stream().findFirst().orElse(null);
        if (run == null) {
            return;
        }
        jdbc.update("UPDATE agent_run SET status='WAITING_APPROVAL' WHERE run_id=?", run[0]);
        appendEvent(run[0], "approval.requested",
                "{\"approvalId\":\"" + approvalId + "\",\"taskKey\":\"" + run[1] + "\"}", now);
    }

    /** 已成功或已取消的任务不会被重新标成就绪。 */
    @Override
    public void markTaskReady(String taskId) {
        jdbc.update("UPDATE agent_task SET status='READY' WHERE task_id=? AND status NOT IN ('SUCCEEDED','CANCELLED')",
                taskId);
    }

    /** 运行取消后，尚未成功的任务一并取消。 */
    @Override
    @Transactional
    public void cancelRun(String runId, String ownerUserId, Instant now) {
        requireOwnedRun(runId, ownerUserId);
        jdbc.update("UPDATE agent_run SET status='CANCELLED',completed_at=? WHERE run_id=?", ts(now), runId);
        jdbc.update("""
                UPDATE agent_task t JOIN agent_plan p ON p.plan_id=t.plan_id
                SET t.status='CANCELLED' WHERE p.run_id=? AND t.status NOT IN ('SUCCEEDED')
                """, runId);
        appendEvent(runId, "run.cancelled", "{\"runId\":\"" + runId + "\"}", now);
    }

    /** 插入一条 PENDING 审批。过期时间由调用方给定。 */
    @Override
    public String requestApproval(String runId, String taskId, String ownerUserId, String actionType,
            String parameterHash, String summary, Instant expiresAt, Instant now) {
        requireOwnedRun(runId, ownerUserId);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_approval(approval_id,run_id,task_id,owner_user_id,action_type,parameter_hash,summary,status,requested_by,expires_at,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, id, runId, taskId, ownerUserId, actionType, parameterHash, summary, "PENDING", ownerUserId,
                ts(expiresAt), ts(now));
        return id;
    }

    /**
     * 只有未使用、未过期且参数哈希一致的 PENDING 审批能消费。
     * 成功后任务回到 READY，运行回到 PLAN_RUNNING。条件不符返回 false。
     */
    @Override
    @Transactional
    public boolean consumeApproval(String approvalId, String ownerUserId, String expectedHash, Instant now) {
        int updated = jdbc.update("""
                UPDATE agent_approval SET status='APPROVED',approved_by=?,used_at=?
                WHERE approval_id=? AND owner_user_id=? AND status='PENDING' AND used_at IS NULL AND expires_at>=? AND parameter_hash=?
                """, ownerUserId, ts(now), approvalId, ownerUserId, ts(now), expectedHash);
        if (updated == 0) {
            return false;
        }
        String taskId = jdbc.queryForObject("SELECT task_id FROM agent_approval WHERE approval_id=?", String.class,
                approvalId);
        String runId = jdbc.queryForObject("SELECT run_id FROM agent_approval WHERE approval_id=?", String.class,
                approvalId);
        jdbc.update("UPDATE agent_task SET status='READY' WHERE task_id=?", taskId);
        jdbc.update("UPDATE agent_run SET status='PLAN_RUNNING' WHERE run_id=?", runId);
        appendEvent(runId, "approval.resolved",
                "{\"approvalId\":\"" + approvalId + "\",\"status\":\"APPROVED\"}", now);
        return true;
    }

    /** 更新 0 行说明审批不存在或不属于该用户。任务和运行改为取消。 */
    @Override
    @Transactional
    public void rejectApproval(String approvalId, String ownerUserId, Instant now) {
        int updated = jdbc.update(
                "UPDATE agent_approval SET status='REJECTED',used_at=? WHERE approval_id=? AND owner_user_id=?",
                ts(now), approvalId, ownerUserId);
        if (updated == 0) {
            throw new AgentRunNotFoundException("approval not found");
        }
        String[] ids = jdbc.query("SELECT run_id,task_id FROM agent_approval WHERE approval_id=?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, approvalId).getFirst();
        jdbc.update("UPDATE agent_task SET status='CANCELLED' WHERE task_id=?", ids[1]);
        jdbc.update("UPDATE agent_run SET status='CANCELLED' WHERE run_id=?", ids[0]);
        appendEvent(ids[0], "approval.resolved",
                "{\"approvalId\":\"" + approvalId + "\",\"status\":\"REJECTED\"}", now);
    }

    /**
     * 锁住已过期且仍在 RUNNING 的领取，把任务放回 READY，并保留租约版本历史。
     * “现在”以数据库时钟为准。
     */
    @Override
    @Transactional
    public int recoverExpiredLeases(Instant now) {
        now = databaseNow();
        List<String[]> expired = jdbc.query("""
                SELECT t.task_id,p.run_id,t.task_key,t.plan_id
                FROM agent_task t
                JOIN agent_task_lease l ON l.task_id=t.task_id
                JOIN agent_plan p ON p.plan_id=t.plan_id
                WHERE t.status='RUNNING' AND l.lease_until<=?
                FOR UPDATE SKIP LOCKED
                """, (rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                ts(now));
        for (String[] row : expired) {
            jdbc.update("UPDATE agent_task SET status='READY' WHERE task_id=? AND status='RUNNING'", row[0]);
            appendEvent(row[1], "task.retrying", "{\"taskKey\":\"" + row[2] + "\"}", now);
            unlockReady(row[3]);
        }
        return expired.size();
    }

    /** 幂等表里已有该键则认为副作用已经成功过，调用方不应再执行。 */
    @Override
    public boolean alreadySucceeded(String executionKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_action_idempotency WHERE idempotency_key=?", Integer.class, executionKey);
        return count != null && count > 0;
    }

    /** 把运行标成成功并写完成事件。不检查任务是否都已结束。 */
    @Override
    @Transactional
    public void markRunSucceeded(String runId, Instant now) {
        jdbc.update("UPDATE agent_run SET status='SUCCEEDED',completed_at=? WHERE run_id=?", ts(now), runId);
        appendEvent(runId, "run.completed", "{\"runId\":\"" + runId + "\"}", now);
    }

    /** 已批准且已使用的审批才算副作用得到授权。 */
    @Override
    public boolean isSideEffectAuthorized(String taskId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_approval
                WHERE task_id=? AND status='APPROVED' AND used_at IS NOT NULL
                """, Integer.class, taskId);
        return count != null && count > 0;
    }

    /** 数据库时钟。工人本机时间不能把过期领取救活。 */
    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant();
    }

    /**
     * 锁住任务和租约直到事务提交，并在拿到锁之后再看时间和版本。
     * 状态不是 RUNNING、工人或版本不符，或截止时间已到，都算租约丢失。
     */
    private void lockClaim(ClaimedTask claim) {
        List<Object[]> rows = jdbc.query("""
                SELECT t.status,l.owner_instance,l.version,l.lease_until
                FROM agent_task t JOIN agent_task_lease l ON l.task_id=t.task_id
                WHERE t.task_id=? FOR UPDATE
                """, (rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getLong(3),
                rs.getTimestamp(4).toInstant()}, claim.taskId());
        if (rows.isEmpty()) {
            throw new TaskLeaseLostException(claim.taskId());
        }
        Object[] row = rows.getFirst();
        if (!"RUNNING".equals(row[0]) || !Objects.equals(claim.workerId(), row[1])
                || claim.leaseVersion() != (Long) row[2] || !databaseNow().isBefore((Instant) row[3])) {
            throw new TaskLeaseLostException(claim.taskId());
        }
    }

    /** 与接管放在同一事务里，写检查点或事件前先确认租约仍在。 */
    @Override
    @Transactional
    public void withLease(ClaimedTask claim, Instant now, Runnable writes) {
        lockClaim(claim);
        writes.run();
    }

    /**
     * 只延长仍然有效的那一代租约。续约失败不会把已过期的租约救回来。
     * 租约短于 3 毫秒直接拒绝。锁定时已经丢失则返回 false。
     */
    @Override
    @Transactional
    public boolean renewLease(ClaimedTask claim, Instant now, Duration lease) {
        if (lease.toMillis() < 3) {
            throw new IllegalArgumentException("lease must be at least 3ms");
        }
        try {
            lockClaim(claim);
        } catch (TaskLeaseLostException lost) {
            return false;
        }
        Instant current = databaseNow();
        return jdbc.update("""
                UPDATE agent_task_lease SET lease_until=?,heartbeat_at=?
                WHERE task_id=? AND owner_instance=? AND version=? AND lease_until>?
                """, ts(current.plus(lease)), ts(current), claim.taskId(), claim.workerId(), claim.leaseVersion(),
                ts(current)) == 1;
    }

    /** 依赖都已成功的 PENDING 任务改为 READY。 */
    private void unlockReady(String planId) {
        List<String> unblock = jdbc.query("""
                SELECT t.task_id FROM agent_task t
                WHERE t.plan_id=? AND t.status='PENDING'
                  AND NOT EXISTS (
                    SELECT 1 FROM agent_task_dependency d
                    JOIN agent_task dep ON dep.plan_id=d.plan_id AND dep.task_key=d.depends_on_task_key
                    WHERE d.plan_id=t.plan_id AND d.task_key=t.task_key AND dep.status<>'SUCCEEDED')
                """, (rs, n) -> rs.getString(1), planId);
        for (String readyId : unblock) {
            jdbc.update("UPDATE agent_task SET status='READY' WHERE task_id=? AND status='PENDING'", readyId);
        }
    }

    /** 任务的能力类型，用于决定要不要补验证或报告事件。 */
    private String capability(String taskId) {
        return jdbc.queryForObject("SELECT capability_type FROM agent_task WHERE task_id=?", String.class, taskId);
    }

    /** 运行查询行。计划或所有者列可以为空。 */
    private AgentRunView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AgentRunView(rs.getString("run_id"), rs.getString("conversation_id"), rs.getString("owner_user_id"),
                rs.getString("status"), rs.getString("execution_mode"), rs.getString("route_reason"),
                rs.getString("plan_id"), rs.getLong("last_event_sequence"));
    }

    /** 序列化失败时写成空对象，避免事件或计划草稿因为坏字段完全写不进去。 */
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** SHA-256 十六进制。null 按空串哈希。算法不可用时视为状态非法。 */
    private String sha(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** JDBC 时间戳。 */
    private Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    /** 超长文本截断。null 保持 null。 */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
