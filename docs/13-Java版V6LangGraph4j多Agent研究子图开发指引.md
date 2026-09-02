# Java 版 V6：LangGraph4j 多 Agent 研究子图开发指引

> 项目：D:\jijing-agent  
> 目标版本：V6.0  
> 前置版本：V4 混合 Agent Runtime 与 V5 有限多 Agent 基线通过  
> 核心目标：不替换现有持久化 Runtime，引入 LangGraph4j 完成复杂研究 Task 内部的状态图编排，打通真实 Spring AI 多 Agent、StructuredArtifact、Checkpoint 恢复和 A/B 评测

## 0. V6 到底解决什么

当前项目已经具备三类基础能力：

1. Spring AI 负责 ChatClient、Tool Calling、Chat Memory 和模型流式响应。
2. 自研 Runtime 负责 Router、Plan、Task DAG、Lease、Approval、Idempotency 和 Event。
3. V5 已定义固定角色、Role Tool ACL、StructuredArtifact 和有限多 Agent 策略。

但当前多 Agent 仍主要是角色和约束骨架：

- MultiAgentSupervisor 生成 RoleBinding，但绑定没有进入真实 Task 执行链。
- PlanTaskWorker 尚未通过 Capability Registry 调用真实业务能力。
- Researcher、Risk、Verifier、Writer 还不是隔离的 Spring AI Agent 实例。
- BoundedReactExecutor 有边界算法，但没有形成可观察、可恢复的复杂研究图。
- 多分支研究没有真实 Fan-out/Fan-in。
- Graph 内节点成功后，进程崩溃仍缺少节点级恢复语义。

V6 只选择一个纵向切片完成这些能力：

~~~text
CATALYST_RESEARCH
  → 任务拆解
  → 行情 / 文档 / 行业三路并行研究
  → Evidence 聚合
  → Verifier 条件判断
  → 最多一次定向补查
  → Writer 生成结构化报告
  → StructuredArtifact
  → 写回现有 agent_task
~~~

V6 不把整个系统迁移到 LangGraph4j。外层业务可靠性继续由现有 Runtime 负责，LangGraph4j 只负责一个复杂 Task 内部的非确定性研究流程。

## 1. 不可破坏的架构边界

### 1.1 三层职责

~~~text
┌──────────────────────────────────────────────────────────────┐
│ 自研 Agent Runtime                                            │
│ Run / Plan / Task / Lease / Approval / Idempotency / Event   │
└──────────────────────────────┬───────────────────────────────┘
                               │ claim 一个复杂 Task
┌──────────────────────────────▼───────────────────────────────┐
│ LangGraph4j                                                   │
│ State / Node / Edge / Parallel / Conditional / Checkpoint    │
└──────────────────────────────┬───────────────────────────────┘
                               │ 节点调用
┌──────────────────────────────▼───────────────────────────────┐
│ Spring AI                                                     │
│ ChatClient / Prompt / Tool Calling / Model / Streaming       │
└──────────────────────────────────────────────────────────────┘
~~~

### 1.2 唯一事实来源

- Run、Plan 和 Task 的最终状态以 `agent_run`、`agent_plan`、`agent_task` 为准。
- LangGraph4j Checkpoint 只表示某个 Task Attempt 内部执行到哪个节点。
- Graph 不能直接把外层 Task 标记为成功。
- 只有 Capability Executor 校验最终 Artifact 并提交后，Repository 才能完成 Task。
- Graph 恢复不能绕过 Task Lease、owner_user_id、plan_version 和 executionKey。
- LangGraph4j Interrupt 不能替代外层 Approval；导出、发布、通知仍使用 `agent_approval`。

### 1.3 确定性任务不进入 Graph

以下能力继续直接调用 Application Service：

~~~text
FUND_PROFILE_QUERY
FUND_NAV_QUERY
FUND_METRICS_QUERY
FUND_COMPARE
WATCHLIST_READ
PORTFOLIO_SNAPSHOT
PORTFOLIO_RETURN
PORTFOLIO_RISK
REALTIME_QUOTE（明确单次查询）
DOCUMENT_SEARCH（明确单次检索）
~~~

第一阶段只有 `CATALYST_RESEARCH` 使用 LangGraph4j。不能为了展示框架把所有 Task 包装成 Graph。

## 2. 范围与非目标

### 2.1 必须完成

1. 真实 Capability Executor Registry，替换 PlanTaskWorker 的占位 Artifact。
2. CATALYST_RESEARCH LangGraph4j 子图。
3. Researcher、Market、Verifier、Writer 独立 Spring AI ChatClient。
4. 角色级 Prompt、Tool ACL、Token 和 Tool 预算。
5. Typed Graph State，禁止共享无限消息历史。
6. 三路研究 Fan-out/Fan-in 和全局并发限制。
7. Verifier 条件边和最多一次定向补查。
8. Graph 节点 Checkpoint、Task Lease 恢复和幂等桥接。
9. StructuredArtifact、Claim、Evidence、Gap 和 VerificationReport 契约。
10. Graph 事件映射到现有 agent_event 和 SSE。
11. 单 Agent / 多 Agent A/B 评测与启用门槛。
12. 默认环境、MySQL、故障恢复和真实模型验收。

### 2.2 不做

- 不替换 ExecutionModeRouter、AgentCoordinator 和外层 Plan DAG。
- 不允许 Graph 创建白名单外 Capability。
- 不做 Agent 自由群聊或动态创建角色。
- 不允许角色动态编写其他角色的 Prompt。
- 不让 Graph 决定用户权限、审批策略或平台预算。
- 不在 Graph State 保存完整第三方响应、整篇文档或跨用户数据。
- 不同时引入 LangChain4j Agentic、Camunda、Temporal 等第二套编排框架。
- 不做自动申购、赎回、转换或调仓。
- 不用框架默认 Memory 替代现有会话记忆和 Fact Card。

## 3. 开工门禁与版本策略

### 3.1 当前版本事实

当前父 POM 使用 Java 21、Spring Boot 3.5.12、Spring AI 1.1.7。

LangGraph4j 的 Spring AI 集成模块可能跟随较新的 Spring AI 主版本。V6 不允许为了接入 Graph 直接升级 Spring Boot 和 Spring AI。

### 3.2 兼容性 Spike

Iteration 0 必须在独立提交中比较两条路线：

优先路线 A：

~~~text
langgraph4j-core
  + 项目自己实现 SpringAiGraphNode
  + 节点内部直接调用现有 ChatClient
~~~

路线 B 仅在依赖树无冲突时采用：

~~~text
langgraph4j-spring-ai
  + 官方 Spring AI Adapter
~~~

路线 B 必须同时满足：

- 不覆盖项目管理的 Spring AI 1.1.7 依赖。
- dependency tree 无 Spring AI、Reactor、Jackson、SLF4J 冲突。
- 现有 SpringAiFundAgentServiceTest 全部通过。
- Tool Calling、Chat Memory 和 SSE 行为不变。
- 不引入第二个 ChatModel 配置体系。

任一条件不满足就采用路线 A。核心 StateGraph 能力不依赖官方 Spring AI Adapter。

父 POM 只增加经过 Spike 固定的 `langgraph4j.version`。版本号必须来自兼容性报告，不能预填未经项目验证的最新版。

验收报告保存为：

~~~text
target/v6-acceptance/dependency-compatibility.json
~~~

至少记录 Spring Boot、Spring AI、LangGraph4j、integrationMode、dependencyConflicts 和 existingAgentTestsPassed。

## 4. 模块与包结构

不新增微服务。主要新增代码位于 `fund-agent-runtime`：

~~~text
com.jijing.fund.agent
├─ capability
│  ├─ AgentCapabilityExecutor
│  ├─ CapabilityExecutionContext
│  ├─ CapabilityExecutionResult
│  └─ CapabilityExecutorRegistry
├─ graph
│  ├─ AgentGraphExecutor
│  ├─ AgentGraphRegistry
│  ├─ GraphExecutionContext
│  ├─ GraphExecutionResult
│  ├─ GraphStopReason
│  └─ checkpoint
│     ├─ AgentGraphCheckpointPort
│     └─ GraphCheckpointKey
├─ graph.catalyst
│  ├─ CatalystResearchGraph
│  ├─ CatalystResearchState
│  ├─ CatalystGraphFactory
│  ├─ CatalystGraphPolicy
│  └─ node
│     ├─ DecomposeNode
│     ├─ MarketResearchNode
│     ├─ DocumentResearchNode
│     ├─ SectorResearchNode
│     ├─ EvidenceMergeNode
│     ├─ VerifyNode
│     ├─ GapRouteNode
│     └─ WriteArtifactNode
├─ subagent
│  ├─ RoleAgent
│  ├─ RoleAgentRegistry
│  ├─ SpringAiRoleAgent
│  ├─ RoleAgentRequest
│  └─ RoleAgentResponse
├─ artifact
│  ├─ ResearchClaim
│  ├─ EvidenceGap
│  ├─ ResearchObservation
│  ├─ ResearchArtifact
│  └─ ResearchArtifactValidator
└─ eval
   ├─ AgentArchitectureVariant
   ├─ MultiAgentEvaluationRunner
   └─ MultiAgentEvaluationReport
~~~

基础设施落点：

~~~text
fund-infrastructure
└─ agent.graph
   ├─ JdbcAgentGraphCheckpointRepository
   ├─ JdbcAgentGraphExecutionRepository
   └─ LocalResearchArtifactStore
~~~

Prompt 作为版本化资源：

~~~text
fund-agent-runtime/src/main/resources/prompts/roles/
├─ researcher-v1.txt
├─ market-analyst-v1.txt
├─ verifier-v1.txt
└─ writer-v1.txt
~~~

## 5. Capability Executor 先行改造

LangGraph4j 接入前，必须先让外层 Task 能调用真实能力。

### 5.1 接口

~~~java
public interface AgentCapabilityExecutor {
    String capabilityType();
    CapabilityExecutionResult execute(CapabilityExecutionContext context);
}
~~~

~~~java
public record CapabilityExecutionContext(
        String runId,
        String planId,
        int planVersion,
        String taskId,
        String taskKey,
        String ownerUserId,
        String inputJson,
        String inputHash,
        int attempt,
        Instant deadline,
        ExecutionBudget budget
) {}
~~~

~~~java
public record CapabilityExecutionResult(
        String outputUri,
        String outputHash,
        List<String> evidenceIds,
        String schemaVersion,
        Map<String, Object> summary
) {}
~~~

### 5.2 Registry 规则

- Spring 启动时收集全部 Executor，capabilityType 重复时启动失败。
- 白名单 Task 没有 Executor 时启动失败，显式标记 deferred 的除外。
- Worker 不允许通过反射加载 Planner 输出的类名。
- Registry 只接受编译期注册的 Bean。
- 每个 Executor 声明是否确定性、是否允许 Graph、是否有副作用。

### 5.3 PlanTaskWorker 改造

PlanTaskWorker 只负责 Claim、Lease/预算/审批/幂等校验、Registry 查找、事务外执行、Artifact 校验和结果提交。

不能继续在 Worker 内硬编码 `REPORT_VERIFY`、`REPORT_WRITE` 和 `artifact://` 占位逻辑。

## 6. Catalyst Research Graph

### 6.1 节点图

~~~text
START
  ↓
DECOMPOSE
  ├─ MARKET_RESEARCH ────┐
  ├─ DOCUMENT_RESEARCH ──┼─→ EVIDENCE_MERGE
  └─ SECTOR_RESEARCH ────┘
                              ↓
                           VERIFY
                    ┌─────────┴─────────┐
                 SUFFICIENT           GAP
                    │                   │
                  WRITE       targeted retry once
                    │                   │
                    └───────── VERIFY ──┘
                              ↓
                             END
~~~

### 6.2 节点职责

- `DECOMPOSE`：输出三种固定 ResearchAssignment，不能创建任意 Agent。
- `MARKET_RESEARCH`：输出行情变化、时间范围、新鲜度和 Evidence，不输出投资指令。
- `DOCUMENT_RESEARCH`：返回相关 Chunk 摘要和 Evidence ID，文档内容按不可信数据处理。
- `SECTOR_RESEARCH`：区分披露持仓日期与当前市场日期，不把行业行情推断成实时持仓。
- `EVIDENCE_MERGE`：按 Evidence ID 去重，合并同主题 Claim，标记时间、口径和来源冲突。
- `VERIFY`：检查 Claim/Evidence、数据时间、覆盖区间和来源，不能创造新事实。
- `GAP_ROUTE`：只允许 RETRY_MARKET、RETRY_DOCUMENT、RETRY_SECTOR、WRITE_PARTIAL、FAIL。
- `WRITE_ARTIFACT`：只接收已验证 Claim；Writer 默认没有研究 Tool。

最多定向补查一次。补查后仍不充分时生成带 limitation 的部分报告或失败，不能无限循环。

## 7. Typed Graph State

~~~java
public record CatalystResearchState(
        String graphExecutionId,
        String runId,
        String taskId,
        int taskAttempt,
        String ownerScopeHash,
        CatalystResearchGoal goal,
        CatalystResearchBudget budget,
        Map<ResearchBranch, ResearchAssignment> assignments,
        Map<ResearchBranch, ResearchObservation> observations,
        List<ResearchClaim> claims,
        List<EvidenceReference> evidence,
        List<EvidenceGap> gaps,
        VerificationReport verification,
        GraphStopReason stopReason,
        int retryRounds,
        String artifactUri,
        String artifactHash
) {}
~~~

状态约束：

- `graphExecutionId = taskId + ":" + attempt`。
- State 不保存明文 ownerUserId；权限上下文由外层注入。
- State 不保存完整 Chat Memory、原始网页、长 JSON 或报告正文。
- 大内容存受控 Artifact Store，State 只保存 URI、Hash 和摘要。
- 每个节点只输出增量 Patch，不能任意覆盖其他分支结果。
- 并行 Map 合并策略必须确定性且可测试。
- State 序列化类型必须显式 allowlist。

默认大小门禁：Graph State 256 KB、Observation 16 KB、Claim 4 KB、Evidence 100 个、Claim 50 个、Gap 20 个。达到上限时先裁剪低相关摘要，不能裁剪 Evidence ID、时间口径和 limitation。

## 8. Spring AI 角色 Agent

### 8.1 独立实例

每个角色必须独立 system prompt、promptVersion、Tool 列表、最大 Tool、最大 Token、超时、输出 Schema 和模型参数。

禁止所有角色共用同一会话 Chat Memory。角色节点只接收当前 Assignment、允许的前置 Artifact 和预算。

### 8.2 RoleToolAcl 双重执行

ACL 必须在两层执行：

1. 构建 ChatClient 请求前只暴露允许的 Tool。
2. Tool 真正执行前使用服务端 RoleExecutionContext 再校验。

模型输出的 role、userId、toolName 不能作为授权依据。

角色边界：

| 角色 | 允许 | 禁止 |
|---|---|---|
| Researcher | 文档检索、公共基金数据 | 用户组合、导出、通知 |
| Market Analyst | 行情、指标、行业数据 | 用户私有数据、写操作 |
| Verifier | 读取 Artifact、检查 Evidence | 创建事实、外部 Tool、写操作 |
| Writer | 读取已验证 Artifact、生成报告 | 研究 Tool、修改 Claim、发布 |

### 8.3 结构化输出

Research Agent 必须返回 `ResearchObservation` JSON，包含 branch、status、summary、claims、warnings、missingFields 和 freshness。

结构化解析失败只允许修复一次；再次失败则当前分支失败，不能把原始模型文本当成可信 Artifact。

## 9. Graph 执行适配

~~~java
public interface AgentGraphExecutor {
    String capabilityType();
    GraphExecutionResult execute(GraphExecutionContext context);
}
~~~

`CatalystResearchCapabilityExecutor` 实现普通 `AgentCapabilityExecutor`，内部调用 `AgentGraphRegistry.require("catalyst-research-v1")`。

节点从受信任的 GraphExecutionContext 获取 runId、taskId、attempt、owner scope、deadline、remaining budget、role、event sink 和 artifact store。这些值由应用注入，不能被模型修改。

Graph 在 Spring Bean 初始化时构建和编译，不要每个请求重新创建。节点名和边名是审计协议，发布后修改需升级 graphVersion。条件路由必须返回枚举，不能使用模型自由输出的节点名。

## 10. 并行、预算与停止条件

- MARKET、DOCUMENT、SECTOR 三个只读分支允许并行。
- Executor 由平台配置注入，Graph 和 Planner 不得指定任意线程数。
- 默认每个 Graph 最大并行分支 3，每个 Plan 最大并行复杂 Task 2。
- 同一外部 Provider 使用共享 Bulkhead 和 RateLimiter。
- 个人组合读取与公共研究状态分离。

预算层级：

~~~text
平台 → 用户 → Run → Task → Graph → Node / Role Agent
~~~

建议初始边界：Graph 60 秒、并行分支 3、补查 1 次、模型调用 6、Tool 调用 12、单分支 Tool 4、总 Token 20,000。

必须持久化 StopReason：

~~~text
COMPLETED
PARTIAL_EVIDENCE
NO_NEW_EVIDENCE
BUDGET_EXHAUSTED
DEADLINE_EXCEEDED
POLICY_REJECTED
VERIFICATION_FAILED
MODEL_UNAVAILABLE
CANCELLED
~~~

## 11. Checkpoint 与双层恢复

外层恢复：

~~~text
Task RUNNING → Lease 过期 → READY → 新 Worker Claim
→ executionKey / planVersion 校验
~~~

内层恢复：

~~~text
taskId + attempt → 最新合法 Graph Checkpoint
→ 校验 graphVersion / inputHash / ownerScopeHash
→ 恢复已完成节点和 Evidence 引用
→ 从未完成节点继续
~~~

恢复规则：

- Checkpoint 必须绑定 taskId、attempt、graphVersion 和 inputHash。
- Task 输入、planVersion 或授权范围变化时不能复用旧 Checkpoint。
- 已成功外部调用通过 Tool Call 幂等键复用，不能只依赖节点状态。
- Checkpoint 成功但 Event 未写时，以 Checkpoint sequence 重建事件。
- 节点结果成功但 Checkpoint 未写时，重试节点通过 executionKey 复用结果。
- SUCCEEDED Task 绝不重新进入 Graph。
- Graph END 不等于 Task SUCCEEDED；Artifact 校验和外层提交后才完成。

不得让第三方 Saver 绕过项目数据规则。实现 `AgentGraphCheckpointPort`，由 infrastructure 适配 LangGraph4j Saver SPI，统一 owner scope、事务、大小限制、脱敏、retention 和审计。

## 12. 数据库迁移 V24～V26

### 12.1 V24：agent_graph_execution

字段：graph_execution_id、run_id、plan_id、task_id、task_attempt、graph_name、graph_version、status、input_hash、owner_scope_hash、stop_reason、started_at、completed_at、created_at、updated_at。

唯一约束：`task_id + task_attempt + graph_version`。

### 12.2 V25：agent_graph_checkpoint

字段：checkpoint_id、graph_execution_id、sequence、node_name、state_json、state_hash、state_schema_version、created_at。

约束：`graph_execution_id + sequence` 唯一，sequence 单调递增；不使用现有 run 级 `agent_checkpoint` 混装 Graph 内状态。

### 12.3 V26：agent_graph_node_execution

字段：node_execution_id、graph_execution_id、node_name、node_attempt、role_name、status、input_hash、output_hash、model_provider、model_name、prompt_version、tool_calls、prompt_tokens、completion_tokens、duration_ms、error_code、started_at、completed_at。

该表不保存完整 Prompt、用户原文、模型思维过程或第三方原始响应。

## 13. Artifact 契约

### 13.1 research-artifact-v1

~~~json
{
  "artifactType": "CATALYST_RESEARCH",
  "schemaVersion": "research-artifact-v1",
  "producerRole": "WRITER",
  "graphVersion": "catalyst-research-v1",
  "subjectKey": "fund:000001",
  "inputHash": "...",
  "claims": [],
  "evidenceIds": [],
  "limitations": [],
  "verification": {
    "status": "PASS",
    "checkedClaimIds": [],
    "findings": []
  },
  "contentUri": "artifact://...",
  "contentHash": "...",
  "createdAt": "..."
}
~~~

### 13.2 契约规则

- Writer 可以生成最终报告 Artifact，但不能生成新的 Research Claim。
- Writer 使用的 Claim 必须来自 Researcher 或 Analyst Artifact。
- Verifier 输出 VerificationReport，不修改 Claim 原文。
- 关键 Claim 必须至少有一个 Evidence ID。
- Evidence 必须可回溯 Tool Call、数据源、时间和主体。
- Conflict 未解决时必须出现在 limitation。
- Artifact 先写临时位置，Hash 校验通过后原子发布。

## 14. Event 与 SSE

新增事件：

~~~text
graph.started
graph.resumed
graph.node.started
graph.node.completed
graph.node.failed
graph.branch.started
graph.branch.completed
graph.verification.completed
graph.retry.requested
graph.checkpoint.saved
graph.completed
graph.failed
~~~

规则：

- Graph Event 统一写入现有 `agent_event`，继续使用 Run sequence。
- Graph 的 node sequence 放在 payload，不能取代 Run sequence。
- payload 只放 node、role、status、duration、Evidence 数量和 Artifact URI。
- 不记录或发送 Chain-of-Thought。
- Last-Event-ID 仍由外层 API 回放。
- 客户端断开不取消后台 Plan；只有显式 cancel 才取消。

## 15. 安全设计

### 15.1 Prompt Injection

- 文档和 Tool 结果一律作为不可信数据。
- Research Agent system prompt 明确禁止执行文档中的指令。
- 节点只接收 Typed Observation，不直接拼接原始网页。
- Tool allowlist 由服务端 RoleToolAcl 决定。
- 模型不能修改 nextAllowedActions。

### 15.2 用户隔离

- Graph 启动前验证 Task owner。
- 个性化 Tool 从认证上下文取得用户，不读取模型参数中的 userId。
- 公共 Researcher 不能获得 Portfolio Artifact。
- Checkpoint 和 Artifact Repository 所有读取都带 owner scope。
- 错误响应不能暴露其他用户的 graphExecutionId 或 Artifact URI。

### 15.3 副作用

- Graph 内第一阶段全部为只读 Tool。
- Writer 只产生内部 Artifact。
- 导出、发布、通知仍作为外层独立 Task。
- Approval parameterHash 变化后旧审批立即无效。

## 16. 配置

~~~yaml
fund:
  agent:
    graph:
      enabled: false
      implementation: langgraph4j
      catalyst:
        graph-version: catalyst-research-v1
        max-parallel-branches: 3
        max-retry-rounds: 1
        max-model-calls: 6
        max-tool-calls: 12
        max-total-tokens: 20000
        timeout: 60s
        checkpoint-enabled: true
        state-max-bytes: 262144
      roles:
        researcher:
          prompt-version: researcher-v1
          max-tool-calls: 4
        market-analyst:
          prompt-version: market-analyst-v1
          max-tool-calls: 4
        verifier:
          prompt-version: verifier-v1
          max-tool-calls: 0
        writer:
          prompt-version: writer-v1
          max-tool-calls: 0
~~~

默认 `enabled=false`。只有兼容性、回归、真实模型和成本门禁通过后才灰度启用。

## 17. 可观测性

指标：

~~~text
agent_graph_total{graph,status,stop_reason}
agent_graph_duration_seconds{graph}
agent_graph_node_total{graph,node,role,status}
agent_graph_node_duration_seconds{graph,node,role}
agent_graph_checkpoint_total{graph,result}
agent_graph_resume_total{graph,result}
agent_graph_branch_parallelism{graph}
agent_graph_retry_total{graph,branch,reason}
agent_graph_state_bytes{graph}
agent_graph_model_calls{graph,role}
agent_graph_tool_calls{graph,role,tool}
agent_graph_tokens{graph,role,type}
agent_graph_evidence_coverage_ratio{graph}
agent_graph_cost_ratio{variant}
~~~

禁止把 userId、runId、taskId、graphExecutionId、基金代码放入指标标签。

Trace 关系：

~~~text
HTTP request span
  → agent run span
    → task span
      → graph span
        → node span
          → model span / tool span
~~~

## 18. A/B 评测与启用策略

### 18.1 对照组

~~~text
SINGLE_AGENT
  Spring AI 单 Agent + 相同 Tool + 相同总预算

MULTI_AGENT_GRAPH
  LangGraph4j + 固定角色 + 相同数据源 + 相近总预算
~~~

不能给多 Agent 更多数据源后宣称是编排带来的质量提升。

### 18.2 指标

- 关键 Claim Evidence 覆盖率。
- 无来源 Claim 比例。
- 时间与口径错误率。
- Tool 重复调用率。
- 平均和 p95 Tool 调用次数。
- 平均和 p95 Token。
- 平均和 p95 延迟。
- 并行分支相对串行的耗时下降。
- Verifier 首次通过率。
- 补查成功率。
- Checkpoint 恢复后重复外部调用数。
- 普通问答误启动 Graph 比例。

### 18.3 启用门槛

首版延续 V5 策略，并增加硬门禁：

~~~text
multiQuality - singleQuality >= 0.05
extraCostRatio <= 1.50
关键 Claim Evidence 覆盖率 = 100%
普通问答误启动 Graph = 0
恢复后已确认成功的副作用重复 = 0
越权 Tool 调用 = 0
无限循环 = 0
~~~

未达到门槛时保留代码和评测，但生产开关保持关闭。

## 19. 测试

### 19.1 单元测试

- Graph START 到 END 可达。
- 条件路由只返回允许枚举。
- 三路分支合并不丢 Evidence。
- 并行分支相同 Evidence 去重。
- Verifier 缺行情证据只重试 MARKET。
- 最多补查一次，无新 Evidence 正确停止。
- Token、Tool、时间预算原子扣减。
- State 大小和序列化类型 allowlist。
- Writer 不能产生 Research Claim。
- RoleToolAcl 在暴露和执行两层生效。

### 19.2 集成测试

- PlanTaskWorker 调用真实 Capability Executor。
- CATALYST_RESEARCH Task 调用 Graph Executor。
- Graph 完成后生成真实 Artifact 并完成外层 Task。
- 多 Worker 只能有一个拥有 Task Lease。
- Checkpoint 与 node execution 同 task/attempt 关联。
- Graph Event 进入 agent_event 且 sequence 单调。
- 用户不能读取其他用户的 Graph、Checkpoint 和 Artifact。
- V1→V26、V23→V26 数据库迁移通过。

### 19.3 故障注入

在以下位置终止进程：

1. MARKET 完成、DOCUMENT 未完成。
2. Tool 调用成功、节点 Checkpoint 前。
3. Checkpoint 成功、Event 写入前。
4. VERIFY 返回补查后。
5. Artifact 写临时文件后、发布前。
6. Graph END 后、Task complete 前。

恢复验收：

- 已成功节点不产生重复外部副作用。
- Evidence 不丢失、不重复。
- Task attempt 和 Graph attempt 可追踪。
- 旧 planVersion 的 Worker 不能回写。
- 取消后 Graph 不能继续提交 Artifact。

### 19.4 真实模型测试

至少覆盖：

- 单基金下跌原因研究。
- 三只基金行业催化剂比较。
- 公告与实时行情存在时间差。
- 文档检索无结果。
- 一个 Provider 超时但其他分支成功。
- Evidence 相互冲突。
- Prompt Injection 文档。
- 用户要求忽略审批或调用禁止 Tool。

## 20. 开发迭代

### Iteration 0：依赖兼容性 Spike（1～2 天）

- 验证 langgraph4j-core 与当前 Spring AI 1.1.7。
- 比较 core adapter 与 spring-ai adapter。
- 输出依赖树和兼容性报告。
- 保证现有 Agent 测试无回归。

### Iteration 1：真实 Capability Executor（3～5 天）

- 引入 Executor Registry。
- 接通确定性查询、计算和搜索能力。
- 移除 Worker 占位 Artifact。
- 加入双事务执行与幂等测试。

### Iteration 2：Typed State 与 Artifact（3～4 天）

- 定义 CatalystResearchState。
- 定义 Observation、Claim、Gap 和 research-artifact-v1。
- 完成序列化、大小和契约测试。

### Iteration 3：Spring AI 角色 Agent（4～6 天）

- 独立 ChatClient、Prompt 和 Tool ACL。
- 结构化输出和一次修复。
- 角色级预算、指标和审计。

### Iteration 4：LangGraph4j 子图（4～6 天）

- 构建节点和条件边。
- 实现三路并行与 Evidence Merge。
- 实现一次补查和 StopReason。
- 接入 Capability Executor。

### Iteration 5：Checkpoint 与恢复（4～6 天）

- 实现 V24～V26。
- 实现 Saver Port 和 JDBC Adapter。
- 桥接 Task Lease 与 Graph resume。
- 完成故障注入。

### Iteration 6：SSE、可观测性和 Web（3～5 天）

- Graph Event 映射。
- DAG 页面展示 Task 内节点。
- 展示 role、duration、Evidence 数量、stopReason。
- 不展示模型思维过程。

### Iteration 7：A/B 与灰度（4～6 天）

- 固定评测数据集。
- 单 Agent / 多 Agent 等预算对照。
- 输出质量、成本、延迟和恢复报告。
- 达标后按配置灰度启用。

单人现实预计 4～6 周。不得为了缩短时间删除 Capability 真实执行、恢复、Evidence 或 A/B 门禁。

## 21. 一键验收

新增：

~~~powershell
.\scripts\verify-v6-langgraph-multiagent.ps1
~~~

脚本依次：

1. 检查 Java、Spring Boot、Spring AI、LangGraph4j 固定版本。
2. 输出 dependency tree 并检查冲突。
3. 运行现有 V4/V5 Agent 回归测试。
4. 执行 V1→V26 和 V23→V26 迁移测试。
5. 运行 Capability Registry 和真实 Executor 合同测试。
6. 运行 Graph 路由、并行、预算和 Artifact 单元测试。
7. 启动两个 Worker 验证 Task Claim 唯一性。
8. 注入 Graph 节点故障并验证 Checkpoint 恢复。
9. 验证跨用户 Graph/Artifact 隔离。
10. 运行单 Agent / 多 Agent A/B 数据集。
11. 可选运行真实模型和真实 Provider 验收。
12. 输出 `target/v6-acceptance/summary.json`。

## 22. 验收清单

- [ ] 普通问答和确定性查询不启动 LangGraph4j。
- [ ] CATALYST_RESEARCH 通过真实 Graph 执行。
- [ ] 三个研究分支真实并行并正确汇合。
- [ ] 各角色拥有独立 Prompt、Tool ACL 和预算。
- [ ] Graph State 是 Typed State，不保存无限聊天历史。
- [ ] Verifier 能按 Evidence Gap 定向补查且最多一次。
- [ ] Writer 不调用研究 Tool、不生成新事实。
- [ ] 最终 Artifact 的关键 Claim Evidence 覆盖率为 100%。
- [ ] Task 状态以外层 Runtime 为唯一事实来源。
- [ ] Lease 过期后 Graph 能从合法 Checkpoint 恢复。
- [ ] 已成功 Tool 调用不会因恢复重复产生副作用。
- [ ] Graph Event 能通过现有 Last-Event-ID 回放。
- [ ] 用户不能读取其他用户的 Graph、Checkpoint 或 Artifact。
- [ ] 模型和文档不能绕过 RoleToolAcl、预算或审批。
- [ ] A/B 证明质量收益达到门槛且成本可接受。
- [ ] 生产开关默认关闭，灰度和回滚可用。

## 23. 简历表达

完成后可根据真实验收结果写：

> 基于 Spring AI 与 LangGraph4j 实现基金催化剂研究多 Agent 子图，将行情、公告和行业研究建模为并行节点，通过 Typed State、条件边、Evidence Gap 定向补查与 Verifier/Writer 角色隔离生成可追溯 StructuredArtifact。

> 设计“业务级持久化 DAG + Task 内 Agent Graph”的双层编排架构：外层自研 Runtime 提供 MySQL Task Lease、幂等、HITL 审批和单调事件流，内层 LangGraph4j 提供并行研究、条件循环、Checkpoint 与节点级恢复，Spring AI 负责模型、Tool Calling 和流式输出。

> 建立单 Agent/多 Agent 等预算 A/B 评测，从 Evidence 覆盖率、Tool 重复率、Token 成本、p95 延迟和故障恢复验证多 Agent 准入，仅在质量收益达到阈值且额外成本受控时启用。

只有真实测得的数据才能补充百分比、延迟和成本数字。

面试必须能讲清：

1. 为什么 LangGraph4j 不替换外层持久化 Runtime。
2. Task Lease 和 Graph Checkpoint 分别解决什么问题。
3. 为什么确定性查询不进入 Graph。
4. Fan-out/Fan-in 的 State 合并如何避免覆盖和丢 Evidence。
5. 如何防止恢复后重复 Tool 调用和副作用。
6. 为什么 Verifier 不能创造事实、Writer 不能调用研究 Tool。
7. Prompt Injection 为什么不能只依赖 system prompt。
8. 多 Agent 的质量收益如何与额外 Token、延迟公平比较。

## 24. 完成定义

V6 完成不等于“POM 里出现 LangGraph4j”。完成意味着：

- 外层 PlanTaskWorker 已执行真实 Capability，不再生成占位结果。
- 一个复杂研究 Task 内部真实使用 LangGraph4j 的 Typed State、并行节点、条件边、受限循环和 Checkpoint。
- 不同角色是真正隔离的 Spring AI Agent，具有独立 Prompt、Tool ACL、预算和结构化输出。
- Agent 之间只通过受控 State、Artifact 和 Evidence 协作，不自由群聊。
- 服务崩溃后外层 Lease 与内层 Checkpoint 能协同恢复，且不重复已确认成功的调用。
- 最终报告可以沿 Run → Task → Graph → Node → Tool Call → Evidence → Claim → Artifact 完整追溯。
- 单 Agent/多 Agent A/B 证明多 Agent 有可测质量收益；不达标时生产保持单 Agent。

满足以上条件后，LangGraph4j 才体现核心能力，项目也才具备可以在简历和面试中经得起追问的多 Agent 工程含量。
