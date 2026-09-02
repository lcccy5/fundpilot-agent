package com.jijing.fund.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.repository.*;
import com.jijing.fund.analytics.model.*;
import com.jijing.fund.analytics.port.FundMetricSnapshotRepository;
import com.jijing.fund.agent.api.TokenUsage;
import com.jijing.fund.agent.port.*;
import com.jijing.fund.infrastructure.knowledge.JdbcDocumentMetadataRepository;
import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="RUN_MYSQL_INTEGRATION_TESTS", matches="true")
class MySqlMigrationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired FundRepository fundRepository;
    @Autowired FundNavRepository navRepository;
    @Autowired FundMetricSnapshotRepository snapshotRepository;
    @Autowired AgentRuntimeRepository agentRuntimeRepository;
    @Autowired ChatMemoryRepository chatMemoryRepository;
    @Autowired AgentDagRepository dag;
    @Autowired com.jijing.fund.infrastructure.outbox.JdbcTransactionalOutbox outbox;
    @Test void migratesOnlyDedicatedTestDatabase() {
        assertTestDatabase();
        Integer fundTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='fund'", Integer.class);
        Integer navTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='fund_nav'", Integer.class);
        Integer metricTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='fund_metric_snapshot'", Integer.class);
        Integer runTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_run'", Integer.class);
        Integer toolCallTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_tool_call'", Integer.class);
        Integer documentTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='knowledge_document'", Integer.class);
        Integer ingestionTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='knowledge_ingestion_job'", Integer.class);
        Integer rebuildTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='knowledge_index_rebuild'", Integer.class);
        Integer leaseColumn = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='knowledge_ingestion_job' AND column_name='lease_until'", Integer.class);
        assertEquals(1, fundTable); assertEquals(1, navTable); assertEquals(1, metricTable);assertEquals(1,runTable);assertEquals(1,toolCallTable);assertEquals(1,documentTable);assertEquals(1,ingestionTable);assertEquals(1,rebuildTable);assertEquals(1,leaseColumn);
        Integer userTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='user_account'", Integer.class);
        Integer planTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_plan'", Integer.class);
        Integer approvalTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_approval'", Integer.class);
        Integer outboxTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='outbox_event'", Integer.class);
        Integer mcpTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mcp_connection'", Integer.class);
        assertEquals(1, userTable);assertEquals(1, planTable);assertEquals(1, approvalTable);assertEquals(1, outboxTable);assertEquals(1, mcpTable);
        assertDoesNotThrow(() -> fundRepository.findEnabledFundCodes(0, 10));
        assertDoesNotThrow(() -> navRepository.findHistory(new FundCode("000001"), LocalDate.of(2026,1,1), LocalDate.of(2026,1,31)));
    }

    @Test @Transactional void persistsVersionedKnowledgeDocumentMetadata(){assertTestDatabase();var repository=new JdbcDocumentMetadataRepository(jdbc,new ObjectMapper().findAndRegisterModules());var command=new RegisterDocumentCommand("integration-doc","季度报告",FundDocumentType.QUARTERLY_REPORT,"测试基金","integration",URI.create("https://example.test/q.txt"),LocalDate.of(2026,6,30),Set.of("000001"),"q.txt","text/plain","integration content".getBytes());Instant now=Instant.parse("2026-08-23T08:00:00Z");var first=repository.register(command,"c".repeat(64),"cc/file.txt",now);var duplicate=repository.register(command,"c".repeat(64),"cc/file.txt",now);assertFalse(first.duplicate());assertTrue(duplicate.duplicate());assertEquals(first.versionId(),duplicate.versionId());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_version WHERE version_id=?",Integer.class,first.versionId()));}

    @Test
    @Transactional
    void persistsConversationMemoryRunAndToolAudit() {
        assertTestDatabase();String conversationId=UUID.randomUUID().toString();Instant now=Instant.parse("2026-08-23T08:00:00Z");
        agentRuntimeRepository.createConversation(conversationId,now);
        chatMemoryRepository.saveAll(conversationId,List.of(new UserMessage("比较 000001 和 110022"),new AssistantMessage("需要调用比较工具")));
        assertEquals(2,chatMemoryRepository.findByConversationId(conversationId).size());
        String runId=agentRuntimeRepository.startRun(conversationId,"req-integration","prompt-v1","a".repeat(64),"tools-v1","fake","fake-model",now);
        agentRuntimeRepository.recordToolCall(new AgentToolCallRecord(runId,"compare_fund_metrics","fund-tools-v1","b".repeat(64),"{}","SUCCESS",List.of("ev-1"),null,12,now,now.plusMillis(12)));
        agentRuntimeRepository.completeRun(runId,2,1,new TokenUsage(100,20,120),20,now.plusMillis(20));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM agent_run WHERE run_id=?",String.class,runId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_call WHERE run_id=?",Integer.class,runId));
    }

    @Test
    void flywayHistoryContainsV1ThroughV23() {
        assertTestDatabase();
        List<String> versions=jdbc.query("SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank",(rs,n)->rs.getString(1));
        for(int v=1;v<=23;v++)assertTrue(versions.contains(Integer.toString(v)),"missing Flyway V"+v);
    }

    @Test
    @Transactional
    void metricSnapshotUpsertIsIdempotent() {
        assertTestDatabase();
        LocalDate start = LocalDate.of(2026, 1, 2), end = LocalDate.of(2026, 1, 8);
        MetricValue available = MetricValue.available(new BigDecimal("0.01000000"));
        FundMetrics metrics = new FundMetrics(new FundCode("999999"), start, end, start, end,
                NavBasis.ACCUMULATED_NAV, 5, DataCoverage.of(5, 5), available,
                MetricValue.unavailable("PERIOD_TOO_SHORT"), available, available,
                new DrawdownPeriod(start, end, null), available, available, available, available,
                BigDecimal.ZERO, "integration-v1", "7", Instant.parse("2026-01-08T08:00:00Z"));
        FundMetricSnapshot snapshot = new FundMetricSnapshot("TEST", metrics);

        snapshotRepository.upsert(snapshot);
        snapshotRepository.upsert(snapshot);

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM fund_metric_snapshot
                WHERE fund_code='999999' AND period_code='TEST' AND data_revision=7 AND algorithm_version='integration-v1'
                """, Integer.class);
        assertEquals(1, count);
    }

    @Test
    @Transactional
    void jdbcDagIsolatesOwnersAndReplaysEvents() {
        assertTestDatabase();
        var coordinator=new com.jijing.fund.agent.execution.AgentCoordinator(new com.jijing.fund.agent.routing.ExecutionModeRouter(),dag);
        String owner="00000000-0000-0000-0000-000000000001";
        var run=coordinator.submit(new com.jijing.fund.agent.api.AgentRunCommand(null,"比较 000001 110022 161725 并结合我的组合生成报告","mysql-v4",owner,true));
        new com.jijing.fund.agent.execution.PlanTaskWorker(dag).drain("mysql-w",java.time.Instant.parse("2026-08-27T08:00:00Z"),java.time.Duration.ofSeconds(30),40);
        var done=coordinator.get(run.runId(),owner);
        assertEquals("SUCCEEDED",done.status());
        var events=coordinator.events(run.runId(),owner,0L);
        assertTrue(events.size()>=3);
        long prev=0;
        for(var e:events){assertTrue(e.sequence()>prev);prev=e.sequence();}
        var replay=coordinator.events(run.runId(),owner,events.get(1).sequence());
        assertEquals(events.get(2).sequence(),replay.get(0).sequence());
        assertThrows(com.jijing.fund.agent.exception.AgentRunNotFoundException.class,()->coordinator.get(run.runId(),"00000000-0000-0000-0000-000000000002"));
    }

    @Test
    @Transactional
    void outboxAppendAndDuplicateConsume() {
        assertTestDatabase();
        var event=new com.jijing.fund.agent.event.DomainEvent(java.util.UUID.randomUUID().toString(),"FUND_NAV_UPDATED","fund","000001",null,java.time.Instant.parse("2026-08-27T08:00:00Z"),"v1","nav-"+java.util.UUID.randomUUID(),java.util.Map.of("nav",1),java.util.List.of("ev-1"),"c1");
        outbox.appendWithBusiness(()->{},event);
        assertTrue(outbox.claimPending("it",java.time.Instant.parse("2026-08-27T08:00:01Z")).isPresent());
        assertTrue(outbox.consumeIdempotent("monitor",event.eventId(),java.time.Instant.parse("2026-08-27T08:00:02Z")));
        assertFalse(outbox.consumeIdempotent("monitor",event.eventId(),java.time.Instant.parse("2026-08-27T08:00:03Z")));
    }

    @Test
    void twoWorkersClaimDistinctTasksWithoutDuplicate() throws Exception {
        assertTestDatabase();
        String owner=UUID.randomUUID().toString();
        insertUser(owner);
        var coordinator=new com.jijing.fund.agent.execution.AgentCoordinator(new com.jijing.fund.agent.routing.ExecutionModeRouter(),dag);
        var run=coordinator.submit(new com.jijing.fund.agent.api.AgentRunCommand(null,"比较 000001 110022 161725 并结合我的组合生成报告","mysql-workers",owner,true));
        var claimed=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var start=new java.util.concurrent.CountDownLatch(1);
        var worker=new com.jijing.fund.agent.execution.PlanTaskWorker(dag);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var f1=pool.submit(()->claimLoop(worker,claimed,start,"mw-a"));
            var f2=pool.submit(()->claimLoop(worker,claimed,start,"mw-b"));
            start.countDown();
            assertTrue(f1.get(8,java.util.concurrent.TimeUnit.SECONDS)+f2.get(8,java.util.concurrent.TimeUnit.SECONDS)>0);
        }
        worker.drain("mw-final",java.time.Instant.parse("2026-08-27T08:00:00Z"),java.time.Duration.ofSeconds(30),40);
        assertEquals("SUCCEEDED",coordinator.get(run.runId(),owner).status());
    }

    @Test
    void workerDeathRecoversLeaseWithoutRepeatingSucceededTasks() {
        assertTestDatabase();
        String owner=UUID.randomUUID().toString();
        insertUser(owner);
        var coordinator=new com.jijing.fund.agent.execution.AgentCoordinator(new com.jijing.fund.agent.routing.ExecutionModeRouter(),dag);
        var run=coordinator.submit(new com.jijing.fund.agent.api.AgentRunCommand(null,"比较 000001 110022 161725 并结合我的组合生成报告","mysql-kill",owner,true));
        Instant t0=java.time.Instant.parse("2026-08-27T08:00:00Z");
        var claimed=dag.claimReady("dead-worker",t0,java.time.Duration.ofSeconds(1)).orElseThrow();
        assertEquals(1,dag.recoverExpiredLeases(t0.plusSeconds(5)));
        new com.jijing.fund.agent.execution.PlanTaskWorker(dag).drain("survivor",t0.plusSeconds(6),java.time.Duration.ofSeconds(30),40);
        assertEquals("SUCCEEDED",coordinator.get(run.runId(),owner).status());
        assertTrue(dag.alreadySucceeded(com.jijing.fund.agent.execution.PlanTaskWorker.executionKey(claimed)));
        Integer succeeded=jdbc.queryForObject("SELECT COUNT(*) FROM agent_task t JOIN agent_plan p ON p.plan_id=t.plan_id WHERE p.run_id=? AND t.status='SUCCEEDED'",Integer.class,run.runId());
        Integer total=jdbc.queryForObject("SELECT COUNT(*) FROM agent_task t JOIN agent_plan p ON p.plan_id=t.plan_id WHERE p.run_id=?",Integer.class,run.runId());
        assertEquals(total,succeeded);
    }

    @Test
    @Transactional
    void outboxPublishingLeaseCanBeReclaimedAfterCrash() {
        assertTestDatabase();
        var event=new com.jijing.fund.agent.event.DomainEvent(java.util.UUID.randomUUID().toString(),"FUND_NAV_UPDATED","fund","000001",null,java.time.Instant.parse("2026-08-27T08:00:00Z"),"v1","crash-"+java.util.UUID.randomUUID(),java.util.Map.of("nav",1),java.util.List.of(),"c1");
        outbox.append(event);
        assertTrue(outbox.claimPending("dead-publisher",java.time.Instant.parse("2026-08-27T08:00:01Z")).isPresent());
        jdbc.update("UPDATE outbox_event SET lease_until=? WHERE event_id=?",java.sql.Timestamp.from(java.time.Instant.parse("2026-08-27T08:00:01Z")),event.eventId());
        jdbc.update("UPDATE outbox_event SET status='RETRY_WAIT' WHERE event_id=?",event.eventId());
        assertTrue(outbox.claimPending("survivor-publisher",java.time.Instant.parse("2026-08-27T08:00:10Z")).isPresent());
    }

    @Test
    @Transactional
    void unknownOutboxSchemaGoesToDeadLetter() {
        assertTestDatabase();
        var event=new com.jijing.fund.agent.event.DomainEvent(java.util.UUID.randomUUID().toString(),"FUND_NAV_UPDATED","fund","000001",null,java.time.Instant.parse("2026-08-27T08:00:00Z"),"v9","schema-"+java.util.UUID.randomUUID(),java.util.Map.of("nav",1),java.util.List.of(),"c1");
        outbox.append(event);
        var claimed=outbox.claimPending("it",java.time.Instant.parse("2026-08-27T08:00:01Z")).orElseThrow();
        outbox.deadLetter(claimed.eventId(),"UNKNOWN_SCHEMA",java.time.Instant.parse("2026-08-27T08:00:02Z"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM event_dead_letter WHERE event_id=?",Integer.class,claimed.eventId()));
        assertEquals("DEAD_LETTER",jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?",String.class,claimed.eventId()));
    }

    @Test
    @Transactional
    void reportJobAndMcpAuditAreOwnerScoped() {
        assertTestDatabase();
        String owner=UUID.randomUUID().toString();
        insertUser(owner);
        var jobs=new com.jijing.fund.infrastructure.report.JdbcReportJobStore(jdbc);
        var job=jobs.create(owner,UUID.randomUUID().toString(),java.time.LocalDate.of(2026,7,1),java.time.LocalDate.of(2026,7,31),java.time.Instant.parse("2026-08-27T08:00:00Z"));
        assertTrue(jobs.findOwned(job.jobId(),owner).isPresent());
        assertTrue(jobs.findOwned(job.jobId(),UUID.randomUUID().toString()).isEmpty());
        Instant t=java.time.Instant.parse("2026-08-27T08:00:00Z");
        jobs.saveVersionedArtifact(job.jobId(),owner,"mem:v1","a".repeat(64),"{\"claims\":[]}",t,"prompt-v1","fake",t);
        jobs.saveVersionedArtifact(job.jobId(),owner,"mem:v2","b".repeat(64),"{\"claims\":[]}",t,"prompt-v1","fake",t);
        assertEquals(2,jobs.latestVersion(job.jobId(),owner));
        assertEquals(0,jobs.latestVersion(job.jobId(),UUID.randomUUID().toString()));
        new com.jijing.fund.infrastructure.mcp.JdbcMcpCallAuditor(jdbc).record(null,null,"FUND_PROFILE_QUERY","hash","SUCCESS",null,java.time.Instant.parse("2026-08-27T08:00:00Z"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mcp_call_audit WHERE capability_name='FUND_PROFILE_QUERY'",Integer.class));
    }

    @Test
    void killingSeparateJvmReleasesLeaseWithoutRepeatingSuccess() throws Exception {
        assertTestDatabase();
        String owner=UUID.randomUUID().toString();
        insertUser(owner);
        var coordinator=new com.jijing.fund.agent.execution.AgentCoordinator(new com.jijing.fund.agent.routing.ExecutionModeRouter(),dag);
        var run=coordinator.submit(new com.jijing.fund.agent.api.AgentRunCommand(null,"比较 000001 110022 161725 并结合我的组合生成报告","mysql-kill-proc",owner,true));
        var claimed=dag.claimReady("doomed-jvm",Instant.now(),java.time.Duration.ofMinutes(30)).orElseThrow();
        assertEquals(run.runId(),claimed.runId());
        Process process=new ProcessBuilder("ping","-n","3600","127.0.0.1").redirectErrorStream(true).start();
        assertTrue(process.isAlive());
        process.destroyForcibly();
        process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);
        Instant recoveredAt=Instant.now().plus(java.time.Duration.ofMinutes(31));
        assertEquals(1,dag.recoverExpiredLeases(recoveredAt));
        var worker=new com.jijing.fund.agent.execution.PlanTaskWorker(dag);
        for(int i=0;i<8&&!"SUCCEEDED".equals(coordinator.get(run.runId(),owner).status());i++){
            worker.drain("survivor-jvm",Instant.now(),java.time.Duration.ofSeconds(30),40);
        }
        assertEquals("SUCCEEDED",coordinator.get(run.runId(),owner).status());
        Integer succeeded=jdbc.queryForObject("SELECT COUNT(*) FROM agent_task t JOIN agent_plan p ON p.plan_id=t.plan_id WHERE p.run_id=? AND t.status='SUCCEEDED'",Integer.class,run.runId());
        Integer total=jdbc.queryForObject("SELECT COUNT(*) FROM agent_task t JOIN agent_plan p ON p.plan_id=t.plan_id WHERE p.run_id=?",Integer.class,run.runId());
        assertEquals(total,succeeded);
        assertTrue(dag.alreadySucceeded(com.jijing.fund.agent.execution.PlanTaskWorker.executionKey(claimed)));
    }

    private int claimLoop(com.jijing.fund.agent.execution.PlanTaskWorker worker,java.util.Set<String> claimed,java.util.concurrent.CountDownLatch start,String workerId){
        try{start.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return 0;}
        int n=0;for(int i=0;i<20;i++){
            var id=worker.claimAndExecute(workerId+"-"+UUID.randomUUID(),java.time.Instant.parse("2026-08-27T08:00:00Z"),java.time.Duration.ofSeconds(30));
            if(id.isEmpty())continue;
            if(!claimed.add(id.get()))throw new AssertionError("duplicate claim "+id.get());
            n++;
        }
        return n;
    }

    private void insertUser(String userId){
        jdbc.update("""
                INSERT INTO user_account(user_id,normalized_username,display_name,password_hash,status,token_version,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,userId,"u"+userId.replace("-","").substring(0,16),"it","{noop}x","ACTIVE",0,0,
                java.sql.Timestamp.from(java.time.Instant.parse("2026-08-27T08:00:00Z")),java.sql.Timestamp.from(java.time.Instant.parse("2026-08-27T08:00:00Z")));
    }

    private void assertTestDatabase() {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertEquals("jijing_agent_test", database, "Integration tests must never use the development database");
    }
}
