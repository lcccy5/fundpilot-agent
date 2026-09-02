# Java 版 V4：混合 Agent Runtime 2.0 开发指引

> 项目：D:\jijing-agent  
> 目标版本：V4.0  
> 前置版本：V3 完成定义通过  
> 核心目标：在 Spring AI 之上实现可路由、可恢复、可审批、可评测的混合 Agent Runtime

## 0. V4 到底解决什么

当前 SpringAiFundAgentService 已经能完成模型调用、Tool Calling、SSE、会话记忆、Fact Card、Evidence 和安全校验，但所有请求基本走同一条同步 Tool Agent 路径：

~~~text
问题 → 动态暴露 Tool → Spring AI 模型循环 → 回答
~~~

这对单基金查询很好，但以下请求开始超出当前运行时边界：

- 比较 5 只基金，分析我的组合风险，再生成一份报告。
- 同时查询多个独立数据源，部分失败后继续其他步骤。
- 任务超过一次 HTTP 请求时长，需要后台执行和断点恢复。
- 高成本、导出或通知动作需要用户确认。
- 页面刷新后需要从 SSE 事件序号继续查看进度。

V4 把入口升级为：

~~~text
Request
  → Execution Mode Router
      ├─ DIRECT / RAG
      ├─ DETERMINISTIC_TOOL
      ├─ BOUNDED_REACT
      └─ PLAN_AND_EXECUTE
  → 统一 Budget / Evidence / Audit / Safety
  → Verifier
  → Answer 或 Versioned Report
~~~

Spring AI 继续负责模型适配、消息结构和 Tool Schema；项目自研路由、循环边界、计划验证、DAG 调度、Checkpoint、审批和恢复。

## 1. 当前基线

### 1.1 已确认

- 12 个 Maven 项目，Agent Runtime 仍位于 fund-agent-runtime。
- Flyway 已到 V14，因此 V4 从 V15 开始。
- Agent 已有 Run、Tool Call、Message、Fact Card 审计。
- AgentExecutionTrace 已有限制工具次数、重复调用、Tool 超时和 Evidence 聚合。
- SpringAiFundAgentService 已支持 REST/SSE、用户归属和客户端取消。
- FundToolRouter 能根据问题动态暴露公开与个性化 Tool。
- V3 个人 Tool 从服务端 ToolContext 获取 AuthenticatedUser。
- 默认 Maven 测试实际执行 70 个并通过，7 个真实环境测试跳过。
- fund-web 在项目自带 Node 22.23.2 下 lint/build 通过。

### 1.2 当前不存在

- ExecutionMode、RouteDecision、Plan、Task、Checkpoint、Approval 类型。
- agent_plan、agent_task、agent_event 等持久化表。
- 可恢复 Worker 和任务 Lease。
- Last-Event-ID 事件回放。
- 独立 Planner、PlanValidator、Verifier、Writer。
- V4 的模式路由与恢复评测集。

### 1.3 开工门禁

V3 至少完成：

- 两个用户之间的 Conversation、Portfolio、Watchlist 和个性化 Tool 隔离测试。
- 组合流水重放与 XIRR 固定样例对账。
- 认证密钥、Refresh Token、跨用户访问和日志脱敏检查。
- V2 real-env 与 V3 用户组合验收报告。

V4 不应该用复杂编排掩盖 V3 的数据和安全缺口。

## 2. 范围与非目标

### 2.1 必须完成

1. 四种执行模式和可解释路由。
2. 受限 ReAct 循环。
3. JSON Schema 计划、PlanValidator 和持久化 DAG。
4. READY Task 调度、有限并行、重试、Lease 和幂等。
5. Checkpoint、重启恢复和单调事件流。
6. Replan、HITL 审批、Verifier 和 Writer。
7. 同步简单问答与异步长任务共存。
8. 模式路由、质量、成本、恢复和安全评测。

### 2.2 不做

- 不做自由协作多 Agent；V5 才引入固定角色。
- 不接 Kafka；单体阶段使用 MySQL Task Queue。
- 不做自动交易，审批也不能解除该禁令。
- 不允许 Planner 生成任意 Java 类名、URL、SQL 或脚本。
- 不让模型决定权限、预算上限或是否需要审批。
- 不将所有问题升级为 Plan-and-Execute。

## 3. 模块和核心组件

不新增 Maven 模块，在 fund-agent-runtime 内按职责拆包：

~~~text
com.jijing.fund.agent
├─ api
│  ├─ AgentRunUseCase
│  ├─ AgentRunView
│  └─ AgentRunEventView
├─ routing
│  ├─ ExecutionMode
│  ├─ RouteFeatures
│  ├─ RouteDecision
│  └─ ExecutionModeRouter
├─ model
│  ├─ AgentModelGateway
│  ├─ ModelRequest
│  └─ ModelResponse
├─ execution
│  ├─ AgentCoordinator
│  ├─ DirectExecutor
│  ├─ DeterministicToolExecutor
│  ├─ BoundedReactExecutor
│  └─ ExecutionBudget
├─ planning
│  ├─ Planner
│  ├─ PlanDraft
│  ├─ PlanValidator
│  ├─ PlanExecutor
│  └─ ReplanPolicy
├─ task
│  ├─ AgentTaskType
│  ├─ AgentCapabilityRegistry
│  ├─ TaskScheduler
│  ├─ TaskLease
│  └─ TaskResult
├─ approval
│  ├─ ApprovalPolicy
│  └─ ApprovalService
├─ verification
│  ├─ RunVerifier
│  └─ ReportWriter
└─ port
   ├─ AgentPlanRepository
   ├─ AgentEventRepository
   ├─ AgentArtifactRepository
   └─ AgentApprovalRepository
~~~

Infrastructure 实现 JDBC Repository；Scheduler 启动 Plan Task Worker；Interface 提供 Run/Plan/Event/Approval API；Bootstrap 负责装配。

### 3.1 Model Gateway

不要让所有执行器直接依赖 ChatClient。定义内部 Port：

~~~java
public interface AgentModelGateway {
    ModelResponse complete(ModelRequest request);
    Flux<ModelDelta> stream(ModelRequest request);
}
~~~

SpringAiAgentModelGateway 在适配层使用 Spring AI。这样可以：

- 给 Planner、ReAct、Verifier 使用不同 Prompt 和模型参数。
- 在测试中用 ScriptedModelGateway 确定性重放。
- 统一 Token、费用、超时、模型版本和响应 Schema 审计。

这不是替换 Spring AI，而是把 Spring AI 放在稳定边界后面。

### 3.2 Capability Registry

计划任务不能通过反射调用 @Tool 方法。建立显式 Registry：

~~~java
AgentCapability {
  capabilityType
  inputSchemaVersion
  requiredRoles
  sideEffectLevel
  defaultTimeout
  maxAttempts
  executor
}
~~~

现有 Tool 与 Plan Task 复用同一个 Application Use Case；Tool 是模型接口，Capability 是运行时接口，二者不是互相调用。

## 4. 执行模式

### 4.1 DIRECT / RAG

运行时枚举仍只有 DIRECT；通过 DirectVariant 区分 NO_TOOL 与 RAG_ONCE。这样“只做一次知识检索”不会被误算成第五种执行模式。

适用：

- 概念解释。
- 不涉及具体基金实时事实。
- 单次知识检索即可回答。

DIRECT 不暴露 Tool；RAG 只执行一次 search_fund_documents，再由 Writer 生成回答。仍执行 Safety 和 Citation Policy。

### 4.2 DETERMINISTIC_TOOL

适用：

- 意图明确。
- 只需 1～2 个确定 Tool。
- 不需要根据 Observation 决定下一步。

例如：

- 查询 000001 基本资料。
- 计算我的默认组合当前收益。
- 比较明确给出的两只基金。

执行器由规则选中 Capability 并校验参数，模型只负责将结构化结果写成自然语言。它比让模型自己选择 Tool 更便宜、稳定且容易评测。

### 4.3 BOUNDED_REACT

适用：

- 需要查一步再判断下一步。
- 通常 2～5 次工具调用。
- 总任务在一次请求生命周期内完成。
- 整体失败后可以安全重跑。

例如：“000001 最近下跌可能和哪些事件有关？”

每轮结构：

~~~text
Goal
→ Model chooses one whitelisted action
→ Capability executes
→ ObservationSummarizer
→ 新 Evidence/缺口判断
→ Continue 或 Stop
~~~

Observation 只包含：

~~~text
status
typed summary
evidenceIds
warnings
missingFields
freshness
nextAllowedActions
~~~

禁止把第三方完整 JSON、整个文档或历史所有 Tool 结果不断塞回模型。

默认边界：

| 边界 | 默认 |
|---|---:|
| 最大轮次 | 5 |
| 最大 Tool 次数 | 6 |
| 同参数重复 | 1 |
| 连续无新 Evidence | 2 次后停止 |
| 总时长 | 30 秒 |
| Replan | 不支持 |
| 后台恢复 | 不支持 |

### 4.4 PLAN_AND_EXECUTE

适用任一条件：

- 3 个以上基金或多个独立目标。
- 要求生成研究报告或导出。
- 预计超过 6 次 Tool。
- 独立步骤适合并行。
- 预计超过同步请求时长。
- 需要审批、暂停、恢复或重试。

例如：“比较这 5 只基金，结合我的组合分析风险，并生成月度研究报告。”

Plan-and-Execute 是持久化任务图，不是更长的 ReAct。

## 5. Execution Mode Router

### 5.1 特征

RouteFeatures 至少包含：

~~~text
fundCount
intentCount
personalDataRequired
documentResearchRequired
freshMarketDataRequired
reportRequested
exportOrNotificationRequested
estimatedToolCalls
backgroundRequested
ambiguityScore
~~~

### 5.2 规则优先级

~~~text
安全拒绝/无权限
  > 必须审批或后台 → PLAN_AND_EXECUTE
  > 多目标/多基金/报告 → PLAN_AND_EXECUTE
  > 需要观察后继续 → BOUNDED_REACT
  > 明确 1～2 Tool → DETERMINISTIC_TOOL
  > 单次知识检索 → DIRECT(RAG_ONCE)
  > DIRECT(NO_TOOL)
~~~

模型只对 ambiguityScore 较高的边界请求给 routeSuggestion。最终决定必须经过规则覆盖。

RouteDecision 持久化：

~~~text
mode
directVariant nullable
routerVersion
featuresJson
matchedRule
modelSuggestion
overrideReason
estimatedBudget
createdAt
~~~

### 5.3 路由评测

至少 200 条，覆盖：

- 简单问题误进 Plan。
- 多基金报告误进 ReAct。
- “我的组合”但只是单次查询。
- 用户故意要求“使用最复杂模式”。
- Prompt Injection 试图绕过审批。

主要指标：

- modeAccuracy。
- unnecessaryPlanRate。
- underPlannedRate。
- averageEstimatedCost / actualCost。
- p95LatencyByMode。

## 6. 数据库迁移 V15～V18

### 6.1 V15：路由与 Run 扩展

为 agent_run 增加：

~~~text
execution_mode
router_version
route_reason
budget_json
deadline_at
parent_run_id
last_event_sequence
~~~

新增 agent_route_decision，保存特征和规则命中。旧 Run 回填 LEGACY_TOOL_AGENT。

### 6.2 V16：Plan 和 DAG

~~~text
agent_plan
agent_plan_version
agent_task
agent_task_dependency
agent_task_attempt
agent_artifact
~~~

关键约束：

- plan_id + version 唯一。
- plan_id + task_key 唯一。
- dependency 不允许自己依赖自己。
- task input_hash、capability_type、schema_version 不可为空。
- Artifact 大内容存对象存储/本地受控目录，数据库保存 URI、Hash、媒体类型和摘要。

### 6.3 V17：Checkpoint、事件与 Lease

~~~text
agent_checkpoint
agent_event
agent_task_lease
~~~

agent_event 使用 run_id + sequence 唯一；sequence 在同一 Run 内单调递增。Checkpoint 保存状态引用和已完成任务集合，不重复保存大段模型文本。

Lease 字段：

~~~text
owner_instance
lease_until
heartbeat_at
attempt
version
~~~

### 6.4 V18：审批、幂等与偏好

~~~text
agent_approval
agent_action_idempotency
user_agent_preference
~~~

审批保存 actionType、parameterHash、summary、requestedBy、approvedBy、expiresAt、usedAt。任何参数变化都产生新 Hash，旧审批自动失效。

用户长期偏好必须显式确认，并记录 source、confirmedAt、version；模型推测不能直接写入。

## 7. Plan 模型和验证

### 7.1 Planner 输出

Planner 只输出符合 JSON Schema 的 PlanDraft：

~~~json
{
  "goal": "compare funds and analyze portfolio",
  "inputSnapshot": {
    "userIdHash": "...",
    "portfolioVersion": 7,
    "dataCutoff": "2026-08-27T07:00:00Z"
  },
  "budget": {
    "maxTasks": 12,
    "maxModelCalls": 6,
    "maxToolCalls": 16,
    "deadlineSeconds": 180
  },
  "tasks": [
    {
      "taskKey": "metrics-000001",
      "taskType": "FUND_METRICS_QUERY",
      "input": {"fundCode": "000001"},
      "dependencies": [],
      "evidenceRequirement": ["FUND_METRICS"]
    }
  ]
}
~~~

Planner 不输出权限、Java 类、Bean 名、SQL、URL、Prompt 或任意 Tool 名。

### 7.2 Task 白名单

V4 首批：

~~~text
FUND_PROFILE_QUERY
FUND_NAV_QUERY
FUND_METRICS_QUERY
FUND_COMPARE
DOCUMENT_SEARCH
REALTIME_QUOTE
SECTOR_OUTLOOK
CATALYST_RESEARCH
WATCHLIST_READ
PORTFOLIO_SNAPSHOT
PORTFOLIO_RETURN
PORTFOLIO_RISK
REPORT_VERIFY
REPORT_WRITE
REPORT_EXPORT
~~~

REPORT_EXPORT 有外部可见副作用，需要审批；读取和计算任务不需要。

### 7.3 PlanValidator

验证顺序：

1. JSON Schema 和字段大小。
2. Task 类型、Schema 版本和 Capability 是否存在。
3. taskKey 唯一，依赖存在且 DAG 无环。
4. 当前用户是否有目标资源权限。
5. 输入是否来自请求或已批准的前置 Artifact。
6. Task/模型/Tool/Token/费用/时长预算。
7. 副作用等级和审批策略。
8. 每个最终 Claim 是否有 Evidence Requirement。

校验失败不执行任何 Task。只允许 Planner 修正一次结构错误。

## 8. DAG 调度和恢复

### 8.1 Task 状态

~~~text
PENDING → READY → RUNNING → SUCCEEDED
                   ├─ RETRY_WAIT → READY
                   ├─ WAITING_APPROVAL → READY
                   ├─ FAILED
                   └─ CANCELLED

依赖失败 → BLOCKED 或 SKIPPED
~~~

Plan 状态：

~~~text
DRAFT / VALIDATED / RUNNING / WAITING_APPROVAL
COMPLETED / PARTIAL / FAILED / CANCELLED / EXPIRED
~~~

### 8.2 Claim

Worker 用 MySQL 事务领取 READY Task：

~~~text
SELECT ... FOR UPDATE SKIP LOCKED
→ 校验依赖和 deadline
→ 写 RUNNING + lease
→ 提交
→ 事务外执行 Capability
→ 新事务写 Attempt、Artifact、Evidence 和状态
~~~

不要在持有数据库行锁时调用模型或第三方服务。

### 8.3 并行

- 默认每个 Plan 并行 4 个只读 Task。
- 同一 Provider 受 Bulkhead/RateLimiter 限制。
- 同一 Portfolio 的写任务串行。
- Writer 必须等待 Verifier。
- 全局并发由配置和指标控制，不由 Planner 决定。

### 8.4 幂等

Task executionKey：

~~~text
sha256(planId + planVersion + taskKey + inputHash + capabilityVersion)
~~~

成功记录存在时恢复过程复用 Artifact，不重复执行。外部副作用同时使用 agent_action_idempotency。

### 8.5 恢复

服务启动后扫描：

- RUNNING 但 Lease 已过期。
- WAITING_APPROVAL 但审批已过期。
- RETRY_WAIT 已到时间。
- Plan deadline 已超时。

只重新领取未确认成功的 Task。SUCCEEDED Task 不能因为进程重启再次执行。

## 9. Replan、审批、Verifier、Writer

### 9.1 Replan

只允许：

- Provider 持续不可用且存在替代 Capability。
- 基金代码/标的消歧失败。
- 必需数据为空。
- Verifier 发现计划缺少必要证据。

最多 1 次；复杂场景最多配置为 2 次。新 PlanVersion 必须保存 diff、原因、旧任务复用关系并重新验证。不能因为模型“不满意答案”无限 Replan。

### 9.2 HITL

需要审批：

- 报告导出/发布。
- 外部通知。
- 超过用户费用阈值的继续执行。
- 对个人数据生成可下载 Artifact。
- 未来任何写操作。

永远禁止：

- 自动申购、赎回、转换或调仓。
- 通过审批解除跨用户访问。
- 通过审批绕过收益保证安全策略。

### 9.3 Verifier

检查：

- 所有必需 Task 是否完成。
- 比较是否使用公共日期区间。
- 净值/行情/披露持仓口径。
- 数据新鲜度和覆盖率。
- Claim 与 Evidence 类型匹配。
- 用户组合 Snapshot 版本是否仍有效。
- 是否存在相互冲突的 Artifact。

Verifier 输出结构化 VerificationReport，不直接生成新事实。

### 9.4 Writer

Writer 只接收通过验证的 Artifact 和 VerificationReport。默认没有外部 Tool 权限。报告中每个关键结论保留 claimId、evidenceIds、limitations。

## 10. API、SSE 和 Web

保留现有同步接口处理 DIRECT/DETERMINISTIC/短 ReAct：

~~~text
POST /api/v1/agent/chat
POST /api/v1/agent/chat/stream
~~~

新增异步 Run：

~~~text
POST /api/v1/agent/runs
GET  /api/v1/agent/runs/{runId}
GET  /api/v1/agent/runs/{runId}/plan
GET  /api/v1/agent/runs/{runId}/events
GET  /api/v1/agent/runs/{runId}/stream
POST /api/v1/agent/runs/{runId}/cancel
POST /api/v1/agent/runs/{runId}/approvals/{approvalId}
POST /api/v1/agent/runs/{runId}/approvals/{approvalId}/reject
~~~

所有查询使用 owner_user_id + run_id。

SSE 事件：

~~~text
run.routed
plan.created / plan.validated / plan.replanned
task.ready / task.started / task.retrying / task.completed / task.failed
approval.requested / approval.resolved / approval.expired
verification.started / verification.completed
report.completed
run.completed / run.failed / run.cancelled
~~~

每个事件包含 sequence。客户端以 Last-Event-ID 重连时先回放数据库事件，再切换实时流；相同 sequence 只展示一次。

Web 增加：

- 模式与路由原因。
- Plan DAG 和 Task 状态。
- Artifact/Evidence Drawer。
- 暂停、取消和审批。
- 页面刷新后的恢复。
- 报告版本和验证结果。

## 11. 预算和配置

~~~yaml
fund:
  agent:
    runtime:
      router-version: hybrid-router-v1
      max-react-rounds: 5
      max-plan-tasks: 20
      max-replans: 1
      max-parallel-tasks-per-plan: 4
      task-lease: 30s
      task-heartbeat: 10s
      default-plan-deadline: 3m
      max-model-calls: 8
      max-tool-calls: 20
      max-total-tokens: 30000
~~~

预算层级：

~~~text
平台上限
  → 用户/套餐上限
    → Run 预算
      → Task 预算
~~~

下层不能扩大上层预算。Token 和费用预估不足时，Run 应降级为部分报告或请求用户确认，而不是无限执行。

## 12. 可观测性和评测

指标：

~~~text
agent_route_total{mode,rule}
agent_route_mismatch_total{expected,actual}
agent_plan_total{status}
agent_task_total{type,status}
agent_task_retry_total{type,reason}
agent_task_lease_expired_total
agent_approval_wait_seconds
agent_replan_total{reason}
agent_recovery_total{result}
agent_run_cost{mode}
agent_evidence_coverage_ratio{mode}
~~~

禁止 userId/runId/planId 进入指标标签。

评测集合：

~~~text
routing-v4.jsonl
react-stop-v4.jsonl
plan-schema-v4.jsonl
plan-validation-v4.jsonl
recovery-v4.jsonl
approval-v4.jsonl
report-evidence-v4.jsonl
~~~

质量门禁：

- 路由准确率 ≥ 95%。
- 简单请求误进 Plan ≤ 2%。
- ReAct 无效重复调用率 = 0。
- 恢复后已成功副作用重复率 = 0。
- 审批绕过率 = 0。
- 最终关键 Claim Evidence 覆盖率 = 100%。

## 13. 测试

### 13.1 单元

- Router 规则优先级。
- DAG 拓扑排序、环检测、缺依赖。
- Budget 原子扣减。
- ReAct 无新 Evidence 停止。
- Replan 次数限制。
- Approval parameterHash 失效。
- Verifier 口径和 Evidence 规则。

### 13.2 集成

- V1→V18 和 V14→V18 迁移。
- 两个 Worker 并发 Claim 不重复。
- Lease 到期后只被一个 Worker 接管。
- Task 成功落库与事件同事务。
- Last-Event-ID 回放无缺失无重复。
- 用户不能读取其他用户的 Plan、Artifact、Approval。

### 13.3 故障注入

- Planner 返回非法 JSON、未知 Task、循环 DAG。
- 模型超时、Provider 429/500、ES/Redis 不可用。
- Task 完成写库前杀进程。
- 写库后 SSE 前杀进程。
- 审批等待期间重启。
- Replan 后旧版本 Worker 回写。

旧版本回写必须通过 planVersion/taskVersion 拒绝。

## 14. 开发迭代

### Iteration 0：V3 门禁（1～2 天）

完成 V3 未收口项并冻结接口、Tool Schema 和数据样例。

### Iteration 1：Model Gateway、路由与模式（3～5 天）

实现 V15、ExecutionModeRouter、Direct/Deterministic 兼容路径和路由评测。

### Iteration 2：受限 ReAct（3～4 天）

显式轮次、Observation、停止原因、预算与回归测试。

### Iteration 3：Plan/Validator/DAG（5～7 天）

实现 V16、Planner Schema、Capability Registry 和验证器。

### Iteration 4：Worker/Lease/Checkpoint（5～7 天）

实现 V17、并行调度、重试、幂等、事件序号和重启恢复。

### Iteration 5：审批/Replan/Verifier/Writer（4～6 天）

实现 V18、HITL、报告 Artifact 和完整 Evidence 传播。

### Iteration 6：API/Web（4～6 天）

异步 Run、DAG、SSE 回放、审批和报告页面。

### Iteration 7：评测与发布（3～5 天）

故障注入、成本/性能、真实模型评测、Runbook 和演示。

单人现实预计 6～8 周。不要压缩掉恢复、审批和评测，否则只剩一个“会生成 JSON 计划”的演示。

## 15. 验收清单

- [ ] 简单问题不会错误进入 Plan-and-Execute。
- [ ] Router 的版本、特征、规则和覆盖原因可查。
- [ ] ReAct 在无新 Evidence 时可靠停止。
- [ ] Planner 不能生成白名单外任务。
- [ ] PlanValidator 拒绝循环、越权、超预算和缺 Evidence 计划。
- [ ] 独立 Task 有限并行，依赖 Task 严格等待。
- [ ] 服务重启后恢复，SUCCEEDED Task 不重复。
- [ ] Approval 参数改变后失效。
- [ ] Last-Event-ID 回放无缺失、无重复。
- [ ] 最终报告可追踪 Plan → Task → Attempt → Artifact → Evidence。
- [ ] 用户无法查看其他用户的 Run、Plan 或 Artifact。
- [ ] 自动交易仍不可用。
- [ ] 默认、real-env、恢复和前端门禁都有报告。

## 16. 一键验收

新增：

~~~powershell
.\scripts\verify-v4-agent-runtime.ps1
~~~

脚本依次：

1. 检查 V3 与真实环境前置门禁。
2. 执行 V1→V18、V14→V18 迁移。
3. 运行 Router、ReAct、PlanValidator 和 Evidence 数据集。
4. 启动两个 Worker 做并发 Claim。
5. 在指定 Task 完成后终止进程并重启。
6. 验证幂等、Checkpoint 和事件回放。
7. 验证审批前不可执行、参数变化审批失效。
8. 运行真实模型长任务，保存成本、时延和质量。
9. 输出 target/v4-acceptance/summary.json。

## 17. 简历表达

> 在 Spring AI 之上自研混合 Agent Runtime，以可解释复杂度路由选择 Direct、确定性 Tool、受限 ReAct 和 Plan-and-Execute；通过 JSON Schema 计划验证、MySQL DAG 调度、Task Lease、Checkpoint、幂等键、HITL 与单调事件流实现可恢复长任务，并以统一 Evidence 将最终报告追溯到每次 Task 与 Tool Call。

面试必须能讲清：

1. ReAct 和 Plan-and-Execute 的边界。
2. 为什么 Planner 不能直接输出 Tool 名和权限。
3. 为什么调用外部服务时不能持有数据库行锁。
4. “至少一次执行”如何通过幂等得到业务上的恰好一次效果。
5. Checkpoint、Event Log 和 Artifact 分别保存什么。
6. 为什么简单问题必须绕开 Planner。

## 18. 完成定义

V4 完成意味着：简单请求仍然快速、便宜；探索问题受限且会停止；复杂研究任务形成经过验证的持久化 DAG，能够并行、重试、暂停、审批和重启恢复；所有模式共享用户隔离、安全、预算和 Evidence；最终报告可以沿 Run、Plan、Task、Attempt、Artifact、Tool Call 追溯，且这些能力有确定性测试、故障注入和真实模型验收证明。
