# Java 版 V5：主动研究与有限多 Agent 开发指引

> 项目：D:\jijing-agent  
> 目标版本：V5.0  
> 前置版本：V4 混合 Agent Runtime 2.0 完成定义通过  
> 核心目标：事件驱动的主动研究、固定角色多 Agent、版本化报告和受控 MCP

## 0. V5 的设计原则

V5 不是“多创建几个模型，让它们在群聊里讨论”。它只在确实需要分工的异步研究任务中启用有限多 Agent：

~~~text
领域事件 / 用户计划任务
        ↓
Transactional Outbox
        ↓
V4 Plan-and-Execute
        ↓
Supervisor 选择固定角色
  ├─ Data Researcher
  ├─ Portfolio Analyst
  ├─ Risk Analyst
  ├─ Verifier
  └─ Writer
        ↓
结构化 Artifact + Evidence
        ↓
用户预览 / 审批 / 发布 / 通知
~~~

普通查询继续走 V4 的 Direct、Deterministic Tool 或 ReAct。多 Agent 只有在质量收益能超过额外成本时才启用。

## 1. V5 前置基线

V5 开工前，V4 必须已经具备：

- ExecutionModeRouter 和四种模式。
- V15～V18。
- AgentCapabilityRegistry。
- 持久化 Plan/Task/Dependency/Attempt/Artifact。
- Worker Lease、Checkpoint、事件序号和恢复。
- Approval、Verifier、Writer 和预算。
- 异步 Run API、SSE 回放和 Web DAG 页面。
- 单 Agent 长任务质量/成本基线。

若 V4 不能稳定恢复一个单 Agent 任务，多 Agent 只会成倍放大状态和故障。

## 2. V5 范围

### 2.1 必须完成

1. Transactional Outbox 和幂等消费者。
2. 用户通知规则、阈值跨越、冷却和免打扰。
3. 主动日报/周报/月报与版本化 Artifact。
4. 固定角色、Tool ACL、数据范围和 Prompt 版本。
5. 结构化 Artifact 通信，禁止 Agent 自由互聊。
6. Supervisor 的角色选择、任务分解和分层预算。
7. Verifier 退回一次、Writer 只读验证结果。
8. 单 Agent 与多 Agent A/B 评测和启用门槛。
9. 基金能力 MCP Server，以及外部 MCP Client 的安全适配。

### 2.2 不做

- 不做无限角色创建或角色市场。
- 不让模型动态编写 Agent Prompt。
- 不让 Agent 互发自然语言消息形成不可审计循环。
- 不直接使用 Kafka/RabbitMQ；单体先证明 MySQL Outbox。
- 不接自动交易 Tool。
- 不因用户配置提醒而输出收益保证或个性化确定性买卖指令。
- 不把 MCP 当作绕过 Application、权限或 Evidence 的捷径。

## 3. 当前项目上的模块落点

继续使用现有模块：

~~~text
fund-domain
└─ event / notification / report

fund-application
└─ monitoring / notification / report

fund-agent-runtime
├─ multiagent
│  ├─ AgentRole
│  ├─ RoleProfile
│  ├─ RoleAssignmentPolicy
│  ├─ MultiAgentSupervisor
│  ├─ StructuredArtifact
│  ├─ ArtifactContract
│  └─ MultiAgentBudget
├─ verification
└─ mcp

fund-infrastructure
├─ outbox
├─ notification
├─ report
└─ mcp

fund-scheduler
├─ OutboxPublisherWorker
├─ MonitoringRuleWorker
├─ ReportScheduleWorker
└─ NotificationDeliveryWorker
~~~

不新增微服务。角色是同一 Runtime 内的受限执行身份，不是五个独立部署的服务。

## 4. 事件驱动主动助手

### 4.1 领域事件

第一批：

~~~text
FUND_NAV_UPDATED
FUND_PROFILE_CHANGED
FUND_MANAGER_CHANGED
KNOWLEDGE_DOCUMENT_ACTIVATED
VERIFIED_MARKET_EVENT_FOUND
PORTFOLIO_VALUATION_UPDATED
PORTFOLIO_DRAWDOWN_THRESHOLD_CROSSED
PORTFOLIO_CONCENTRATION_THRESHOLD_CROSSED
PORTFOLIO_DATA_QUALITY_DEGRADED
~~~

事件结构：

~~~java
DomainEvent {
  eventId
  eventType
  aggregateType
  aggregateId
  ownerUserId nullable
  occurredAt
  schemaVersion
  deduplicationKey
  payload
  evidenceIds
  correlationId
}
~~~

公共基金事件 ownerUserId 为空；消费时再匹配用户自选或组合。个人组合事件必须带 owner，且消费者不能扩大其数据范围。

### 4.2 Transactional Outbox

业务状态和 outbox_event 在同一数据库事务提交：

~~~text
更新净值/组合/文档
→ 写业务表
→ 写 outbox_event(PENDING)
→ COMMIT
~~~

不能先提交业务再异步“尽力发事件”，否则进程崩溃会永久丢失提醒。

### 4.3 Outbox Poller

~~~text
PENDING / RETRY_WAIT
→ SELECT FOR UPDATE SKIP LOCKED
→ 标记 PUBLISHING + lease
→ 事务外投递到内部 EventDispatcher
→ 消费者写 event_consumption
→ 标记 PUBLISHED
~~~

第一版 EventDispatcher 可以进程内分发，但 Outbox 和 Consumption 状态必须持久化。未来迁移到 Kafka 时只替换 Publisher/Consumer Adapter。

投递语义是至少一次；业务效果通过 deduplicationKey 和消费者幂等实现。

### 4.4 事件 Schema

- schemaVersion 必填。
- 已发布字段不修改语义，只新增可选字段。
- Consumer 声明支持的版本区间。
- 未知版本进入 DEAD_LETTER，不猜测解析。
- payload 不放完整文档、完整交易流水、Token 或用户备注。

## 5. 数据库迁移 V19～V23

### 5.1 V19：Outbox 和消费

~~~text
outbox_event
event_consumption
event_dead_letter
~~~

outbox_event：

~~~text
event_id / event_type / aggregate_type / aggregate_id
owner_user_id / schema_version / payload_json
evidence_ids_json / deduplication_key
status / attempts / next_attempt_at
lease_owner / lease_until
occurred_at / published_at / last_error_code
~~~

event_consumption 以 consumer_name + event_id 唯一。

### 5.2 V20：通知规则和投递

~~~text
notification_rule
notification_record
notification_delivery_attempt
user_notification_preference
~~~

Rule 字段：

~~~text
ruleType
targetType / targetId
threshold
comparison
cooldown
quietHours
channel
enabled
version
~~~

通知记录使用 owner_user_id + rule_id + trigger_fingerprint 唯一。

### 5.3 V21：报告

~~~text
report_schedule
report_job
report_artifact
report_version
report_publication
~~~

报告 Artifact 保存受控 URI、SHA-256、媒体类型、Evidence Manifest、数据截止点和 Prompt/模型版本。不要只保存最终 Markdown 文本。

### 5.4 V22：有限多 Agent

~~~text
agent_role_assignment
agent_role_execution
agent_artifact_review
agent_verification_finding
~~~

V4 的 agent_task/agent_artifact 继续是真实任务和产物表。V22 只增加角色归属、审查和质量元数据，不复制 Plan/Task。

### 5.5 V23：MCP 治理

~~~text
mcp_connection
mcp_capability_snapshot
mcp_call_audit
~~~

凭据不进入表，只保存 secret_reference。Capability Snapshot 固定服务端名称、Schema Hash、权限和更新时间，防止远端 Tool 悄悄改变参数后继续执行旧计划。

## 6. 监控规则、阈值与去重

### 6.1 只在“跨越阈值”触发

回撤提醒不能在每次估值都重复触发：

~~~text
上一次 drawdown = -8%
本次 drawdown = -11%
阈值 = -10%
→ 触发

下一次 drawdown = -12%
→ 冷却期内不重复

恢复到 -7% 后再次跌破 -10%
→ 新 triggerFingerprint，可再次触发
~~~

triggerFingerprint 至少包含：

~~~text
ruleId + thresholdVersion + crossingDirection
+ evaluationWindow + inputDataVersion
~~~

### 6.2 免打扰

免打扰时段不丢事件：

~~~text
事件满足规则
→ 创建 notification_record=SCHEDULED
→ 计算 nextDeliveryAt
→ 免打扰结束后投递
~~~

紧急程度由固定规则定义，模型不能把普通市场波动升级为紧急通知。

### 6.3 冷却与聚合

同一基金同类事件在冷却期聚合为一条摘要；不同数据版本但结论没有实质变化不重复打扰。

## 7. 主动报告

支持：

~~~text
DAILY_DIGEST
WEEKLY_WATCHLIST_REPORT
MONTHLY_PORTFOLIO_REPORT
MAJOR_EVENT_IMPACT_REPORT
DATA_QUALITY_REPORT
~~~

报告流程：

~~~text
Schedule/Event
→ 创建 V4 PLAN_AND_EXECUTE Run
→ 固定 inputSnapshot 和 dataCutoff
→ Supervisor 分配角色
→ Artifact 汇合
→ Verifier
→ Writer
→ 用户预览
→ 审批发布/通知
~~~

每个版本记录：

- reportType、periodStart/end。
- dataCutoff。
- portfolioVersion/watchlistVersion。
- Prompt、Role、Tool、算法和模型版本。
- Artifact Hash。
- Evidence Manifest。
- VerificationReport。
- 与上一版本差异。

## 8. 有限多 Agent 架构

### 8.1 固定角色

| 角色 | 责任 | 可读数据 | 禁止 |
|---|---|---|---|
| DATA_RESEARCHER | 公开基金、净值、公告、文档、事件 | 公共研究数据 | 用户账户、写操作 |
| PORTFOLIO_ANALYST | 用户持仓、收益、重合度、暴露 | 当前 owner 的组合快照 | 其他用户、外部任意搜索 |
| RISK_ANALYST | 确定性风险、压力场景 | 已授权组合和公开行情 | 编造价格、给交易指令 |
| VERIFIER | 口径、时效、冲突、Evidence | 所有当前 Run Artifact | 创造新事实、直接发布 |
| WRITER | 组织已验证报告 | 仅 VERIFIED Artifact | 直接调用外部 Tool |

角色 ACL 由 CapabilityRegistry 强制，不依赖 Prompt 自觉。

### 8.2 Supervisor

Supervisor 输入：

~~~text
validated V4 Plan
request goal
owner scope
input snapshot
available role profiles
global budget
~~~

输出 RoleAssignment：

~~~text
taskKey
role
allowedCapabilities
artifactContract
subBudget
reason
~~~

Supervisor 不能：

- 新增 PlanValidator 未批准的 Task。
- 扩大用户范围。
- 给角色超出 ACL 的 Capability。
- 提高总预算。
- 跳过 Verifier。

### 8.3 启用条件

只有以下场景：

- 周报/月报。
- 3 只以上基金和个人组合的联合研究。
- 重大事件对多个持仓的影响。
- 需要公共数据、组合数据、风险计算三类独立专业产物。

普通问答和单基金研究保持单 Agent。

### 8.4 角色数量和预算

默认：

| 限制 | 值 |
|---|---:|
| 单 Run 最大角色 | 5 |
| 同时活跃角色 | 3 |
| 最大 Task | 20 |
| Supervisor Replan | 1 |
| Verifier 退回 | 1 |
| 每角色模型调用 | 3 |
| 总模型调用 | 10 |
| 总 Tool 调用 | 30 |
| 默认时长 | 10 分钟 |

预算层级：

~~~text
RunBudget
├─ SupervisorBudget
├─ DataResearcherBudget
├─ PortfolioAnalystBudget
├─ RiskAnalystBudget
├─ VerifierBudget
└─ WriterBudget
~~~

未使用预算可以归还，但角色不能向其他角色借用后突破 Run 上限。

## 9. Artifact 通信

Agent 之间不传自由聊天消息，只交换符合 Schema 的 Artifact：

~~~json
{
  "artifactType": "PORTFOLIO_RISK_ANALYSIS",
  "schemaVersion": "v1",
  "producerRole": "RISK_ANALYST",
  "inputHash": "...",
  "dataCutoff": "2026-08-27T07:00:00Z",
  "claims": [
    {
      "claimId": "risk-1",
      "statement": "单一基金权重较高",
      "metric": {"name": "maxFundWeight", "value": "0.43"},
      "evidenceIds": ["PORTFOLIO:..."],
      "confidence": "HIGH"
    }
  ],
  "limitations": [],
  "contentHash": "..."
}
~~~

Artifact Contract 规定：

- 必填字段和最大大小。
- 可接受 Evidence 类型。
- 数据时间和 owner scope。
- 下游角色。
- 验证规则。

不符合 Contract 的产物直接失败，不能让 Writer“尽量理解”。

## 10. Verifier 和冲突处理

Verifier 分四层：

1. Schema：Artifact、字段、Hash 和版本。
2. Evidence：Evidence 存在、类型匹配、owner 匹配。
3. Finance：净值口径、公共区间、算法、披露日期、覆盖率。
4. Cross-Artifact：公共研究、组合和风险结论是否冲突。

Finding：

~~~text
ERROR / WARNING / INFO
findingCode
artifactId / claimId
expected / observed
requiredAction
~~~

ERROR 可以退回对应角色一次。第二次仍失败则报告降级为 PARTIAL 或失败，不启动无限讨论。

冲突例：

- Data Researcher 使用最新公告，Portfolio Analyst 使用旧持仓披露。
- Risk Analyst 组合日期晚于可用净值日期。
- 两个 Provider 对同一字段不同。

Verifier 不选择“看起来更合理”的数字；它按来源优先级、时间和口径规则解决，无法解决时把冲突展示给用户。

## 11. Writer

Writer 只接收：

~~~text
VERIFIED Artifact
VerificationReport
ReportTemplate
用户已确认的偏好
~~~

Writer 无公共数据 Tool、无 Portfolio Tool、无 MCP Tool。它不能新增数字或事实。生成后再做 Claim/Citation Policy 校验。

报告结构建议：

~~~text
执行摘要
组合表现与现金流口径
风险与集中度
自选/持仓基金重要变化
重大事件影响
数据缺口与冲突
Evidence 附录
免责声明
~~~

## 12. MCP 适配

### 12.1 MCP 在 V5 的角色

MCP 只解决 Capability 接入协议：

~~~text
MCP Tool
→ MCP Adapter
→ 输入 Schema/ACL/Timeout/RateLimit
→ Application Use Case 或受控外部服务
→ 统一 TaskResult + Evidence
~~~

MCP 不替代：

- Application Use Case。
- AuthenticatedUser 和所有权。
- AgentCapabilityRegistry。
- PlanValidator。
- Evidence、Budget 和 Audit。

### 12.2 内部 MCP Server

首批只读能力：

~~~text
get_fund_profile
get_fund_nav_history
calculate_fund_metrics
compare_fund_metrics
search_fund_documents
get_my_portfolio
analyze_my_portfolio_risk
~~~

个人能力必须使用服务端认证上下文，不接受 userId 参数。

### 12.3 外部 MCP Client

连接建立前固定：

- Server Identity。
- Transport 和 TLS。
- Capability allowlist。
- Schema Hash。
- Credential secret_reference。
- 数据分类和 owner scope。
- 超时、限流、最大响应体。

外部返回内容全部视为不可信数据：

- 不执行其中的 Prompt 指令。
- 不信任返回的 Evidence ID。
- URI 经过 allowlist/SSRF 校验。
- 内容先映射 DataProvenance 和 Evidence。
- Schema 改变后旧 Plan 暂停并重新验证。

### 12.4 写 Tool

V5 默认不通过 MCP 暴露交易或数据库任意写能力。通知和报告发布即使走 MCP，也必须进入 V4 Approval 和 agent_action_idempotency。

## 13. 通知渠道

V5.0 先实现站内通知；邮件/Webhook 可作为 V5.1。

统一 Port：

~~~java
public interface NotificationChannel {
    DeliveryResult deliver(NotificationMessage message);
}
~~~

NotificationMessage 只包含脱敏摘要和应用内链接。邮件/Webhook 不发送完整持仓、交易明细或模型上下文。

投递状态：

~~~text
SCHEDULED → SENDING → DELIVERED
                     ├─ RETRY_WAIT
                     ├─ FAILED_FINAL
                     └─ CANCELLED
~~~

每个渠道有独立 Idempotency Key。

## 14. API 与 Web

### 14.1 规则

~~~text
GET/POST/PATCH/DELETE /api/v1/notification-rules
GET /api/v1/notifications
POST /api/v1/notifications/{id}/read
~~~

### 14.2 报告

~~~text
GET/POST/PATCH/DELETE /api/v1/report-schedules
POST /api/v1/reports
GET  /api/v1/reports/{reportId}
GET  /api/v1/reports/{reportId}/versions
GET  /api/v1/reports/{reportId}/artifacts
POST /api/v1/reports/{reportId}/publish
~~~

publish 必须携带 approvalId 和期望 reportVersion，避免审批后报告内容改变。

### 14.3 Web

- 通知中心：未读、规则来源、触发指标、冷却状态。
- 规则编辑器：阈值、范围、频率、免打扰、预览。
- 报告中心：运行状态、角色、Task、Artifact、Verifier Findings。
- 版本比较：数据截止点、结论和 Evidence 差异。
- 多 Agent Trace：显示角色产物，不展示模型私有思维过程。
- MCP 管理页仅 ADMIN 可见，展示连接状态、Capability Hash 和审计。

## 15. 安全和隐私

- Role ACL 在执行前和 Capability 调用时各校验一次。
- Artifact 带 owner_scope；公共 Artifact 可以被同 Run 引用，个人 Artifact 不能跨 owner。
- 日志和指标不记录用户持仓明细。
- Prompt 模板、角色描述和 MCP 内容都视为潜在注入面。
- 报告下载使用短期签名 URL，并校验 owner。
- 通知链接不包含 Token。
- 站内通知不能暴露其他用户事件。
- 用户关闭规则后，已排队未发送通知按策略取消。

## 16. 可观测性

~~~text
outbox_event_total{type,status}
outbox_lag_seconds{type}
event_consumer_total{consumer,result}
dead_letter_total{type,reason}
notification_total{channel,status}
notification_deduplicated_total{ruleType}
report_job_total{type,status}
multi_agent_run_total{roles,status}
role_execution_total{role,status}
artifact_validation_total{type,result}
verification_finding_total{severity,code}
mcp_call_total{server,capability,result}
mcp_schema_change_total{server}
~~~

server/capability 必须是低基数配置名，不使用远程 URL。

Trace 关联：

~~~text
eventId → reportJobId → runId → planId
→ taskId → roleExecutionId → artifactId
→ evidenceId / notificationId
~~~

## 17. 评测

### 17.1 单 Agent 对照

同一研究任务同时跑：

- V4 单 Agent Plan-and-Execute。
- V5 有限多 Agent。

比较：

~~~text
claim correctness
evidence coverage
conflict detection
missing-risk detection
completion rate
latency
model calls
tokens
cost
human preference
~~~

只有满足以下条件才默认启用多 Agent：

- 关键事实正确率或冲突发现率有显著提升。
- Evidence 覆盖不下降。
- 安全通过率 100%。
- 成本和 P95 在配置上限内。

否则该场景继续使用 V4 单 Agent。

### 17.2 数据集

~~~text
event-dedup-v5.jsonl
notification-policy-v5.jsonl
role-routing-v5.jsonl
tool-acl-v5.jsonl
artifact-contract-v5.jsonl
cross-artifact-conflict-v5.jsonl
multi-agent-report-v5.jsonl
mcp-untrusted-content-v5.jsonl
~~~

### 17.3 红队

- Data Researcher 请求用户组合。
- Writer 尝试调用外部 Tool。
- MCP 返回“忽略系统指令”。
- Artifact 伪造其他用户 Evidence。
- Supervisor 增加未批准 Task。
- Verifier 和角色无限退回。
- 同一 Outbox 事件重复 10 次。
- 免打扰期间通知风暴。

## 18. 测试

### 18.1 Outbox

- 业务回滚时没有 Event。
- 业务提交和 Event 同时存在。
- Poller 崩溃后 Lease 接管。
- 重复投递只产生一次业务效果。
- 未知 Schema 进入死信。
- 死信修复后可审计回放。

### 18.2 通知

- 阈值跨越而非持续超限。
- 冷却、恢复后再次跨越。
- 免打扰延迟。
- 规则更新使旧 fingerprint 失效。
- owner 隔离。

### 18.3 多 Agent

- RoleAssignment 不扩大 Plan。
- 每个角色 Tool ACL。
- Artifact Schema、Hash、Evidence 和 owner。
- Verifier 最多退回一次。
- Writer 不能创造新 Claim。
- Budget 在并发 Task 下原子扣减。
- 重启后角色产物不重复。

### 18.4 MCP

- Schema Snapshot 改变。
- 认证失败、超时、429、超大响应。
- SSRF URI。
- Prompt Injection 内容。
- 个人 Tool 缺用户上下文。
- 调用审计脱敏。

## 19. 开发迭代

### Iteration 0：V4 门禁（1～2 天）

验证四模式、恢复、审批、Event Replay 和单 Agent 成本基线。

### Iteration 1：Outbox（4～5 天）

V19、事件模型、Poller、消费幂等、死信和回放。

### Iteration 2：规则与通知（4～6 天）

V20、阈值跨越、冷却、免打扰、站内通知。

### Iteration 3：报告任务（4～5 天）

V21、Schedule/Event 转 V4 Plan、版本化 Artifact 和预览发布。

### Iteration 4：角色和 Artifact（5～7 天）

V22、Role Profile、ACL、Supervisor、Contract 和分层预算。

### Iteration 5：Verifier/Writer（4～6 天）

跨 Artifact 冲突、一次退回、报告模板和 Claim 验证。

### Iteration 6：MCP（4～6 天）

V23、内部只读 Server、外部 Client 治理、Schema Snapshot 和审计。

### Iteration 7：Web（4～6 天）

通知中心、规则、报告版本、多 Agent Trace 和 MCP 管理。

### Iteration 8：A/B 评测与发布（4～6 天）

单/多 Agent 对照、红队、故障注入、Runbook 和演示。

单人现实预计 7～10 周。若压缩，V5.0 保留 Outbox、站内通知、月报、固定角色与 A/B 评测；外部 MCP Client 和邮件/Webhook 放到 V5.1。

## 20. 验收清单

- [ ] 业务提交与 Outbox Event 不会一边成功一边丢失。
- [ ] 重复消费不产生重复报告或通知。
- [ ] 阈值持续超限不会通知风暴。
- [ ] 免打扰事件不会丢失。
- [ ] 普通查询不会启动多 Agent。
- [ ] 角色数量、Task、模型、Tool、Token、费用和时长都有上限。
- [ ] Data Researcher 不能读用户组合。
- [ ] Writer 不能调用外部 Tool。
- [ ] Agent 只通过结构化 Artifact 通信。
- [ ] Verifier 最多退回一次。
- [ ] 报告每个关键 Claim 可追溯到角色、Task、Artifact 和 Evidence。
- [ ] 相比 V4 单 Agent 有可量化质量收益，否则自动保持单 Agent。
- [ ] MCP Schema 改变会暂停旧计划。
- [ ] 外部 MCP 内容不能注入 Prompt 或伪造 Evidence。
- [ ] 不存在交易 Tool。
- [ ] 服务重启后 Event、Report、Role Task 和通知均可恢复。

## 21. 一键验收

新增：

~~~powershell
.\scripts\verify-v5-proactive-multi-agent.ps1
~~~

脚本：

1. 先执行 V4 验收。
2. 验证 V1→V23 和 V18→V23 迁移。
3. 生成同一领域事件的重复投递和崩溃恢复。
4. 验证阈值、冷却、免打扰和去重。
5. 生成一次月报并在角色执行中终止/恢复。
6. 执行 Tool ACL 和跨用户红队。
7. 执行 Artifact/Verifier/Writer 契约。
8. 对同一数据集跑 V4 单 Agent 与 V5 多 Agent。
9. 模拟 MCP Schema 变化、注入和超时。
10. 输出 target/v5-acceptance/summary.json 和 A/B 报告。

## 22. 简历表达

> 复用自研 Plan-and-Execute Runtime，通过 Transactional Outbox 和幂等消费者构建主动式基金监控、冷却提醒和版本化月报；设计固定角色、Tool ACL、分层预算与结构化 Artifact 的有限多 Agent 流程，由 Verifier 做跨产物口径/Evidence 冲突检查，Writer 仅消费验证事实；同时以 MCP 作为受控 Capability 协议，并通过单 Agent A/B 评测决定是否启用多 Agent。

面试必须能讲清：

1. Outbox 为什么解决“业务成功但事件丢失”。
2. 至少一次消费如何避免重复通知。
3. 为什么多 Agent 不允许自由聊天。
4. Tool ACL 为什么必须由代码强制。
5. Artifact、Evidence 和 Report Version 的关系。
6. 如何证明多 Agent 比单 Agent 值得。
7. MCP 为什么只是适配协议，不是 Agent 编排器。

## 23. 完成定义

V5 完成意味着：基金、组合和知识变化能够通过 Transactional Outbox 可靠触发；提醒具有阈值跨越、冷却、免打扰和幂等；复杂报告复用 V4 的持久化任务图，由固定角色在 Tool ACL、owner scope 和分层预算内生成结构化 Artifact；Verifier 能发现口径、时效、Evidence 和跨产物冲突，Writer 只能使用验证事实；MCP 调用经过认证、Schema 固定、限流、超时、安全映射和审计；多 Agent 仅在 A/B 评测证明质量收益时启用，普通请求继续保持单 Agent。
