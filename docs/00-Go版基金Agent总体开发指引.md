# 基金分析 Agent（Go 版）总体开发指引

> 本文用于从零开发 Go 版本。它复用当前 Java 项目已经验证的业务边界、数据口径和 Agent 可信原则，但不照搬 Spring 分层与实现方式。

## 1. 项目目标

构建一个面向基金爱好者的基金数据分析与组合风险助手，具备以下能力：

- 查询基金资料、历史净值、区间收益、最大回撤、波动率和夏普比率。
- 比较 2～10 只基金的收益与风险，保证比较区间和数据口径公平。
- 管理自选组合和持仓，分析行业暴露、持仓重合度与集中风险。
- 使用 Agent 自动选择基金数据 Tool，生成带来源、数据时间和证据编号的回答。
- 对复杂请求进行计划拆解、DAG 执行、受限并发、失败重试和局部重规划。
- 对敏感写操作执行 HITL 人工审批。
- 摄取基金公告、季报、招募说明书等文档，提供混合检索和页码级引用。
- 支持流式回答、定时同步、风险提醒、审计、评测和可观测性。

本项目只提供信息分析，不构成投资建议，不实现自动申购、赎回或交易。

## 2. 不能破坏的核心原则

### 2.1 模型不负责确定性计算

收益、回撤、波动率、夏普比率、持仓重合度等必须由 Go 代码计算。LLM 只负责：

- 理解问题；
- 选择 Tool；
- 制定复杂任务计划；
- 解释已经计算出的结果；
- 组织最终报告。

### 2.2 模型不能直接访问基础设施

Agent Tool 只能调用 Application Use Case，禁止直接访问：

- MySQL；
- Redis；
- 任意 URL；
- 任意 SQL；
- Shell；
- 用户未授权的组合与持仓。

### 2.3 回答必须可追溯

所有结论都要标记证据类型：

| 类型 | 含义 |
| --- | --- |
| `FACT` | 数据源直接返回或基金正式披露的事实 |
| `ESTIMATE` | 盘中估值或后端计算得到的估算结果 |
| `INFERENCE` | 模型基于事实与估算作出的解释 |

回答必须携带数据来源、采集时间、数据版本、算法版本和证据 ID。没有足够数据时返回“不可用”或拒答，不能用数值 `0` 冒充有效结果。

### 2.4 场外净值与实时行情必须分开

- 场外基金官方净值通常是日终披露数据。
- 盘中估值必须标记为 `ESTIMATE`。
- ETF、LOF 等场内行情必须附带行情时间和数据源。
- 历史净值修订后必须产生新数据版本，使旧缓存和旧指标失效。

## 3. 技术基线

| 能力 | Go 方案 |
| --- | --- |
| HTTP API | Gin |
| 配置 | 环境变量 + YAML，本地密钥不入库 |
| 数据库 | MySQL + `database/sql` + sqlc |
| 数据迁移 | Goose 或 golang-migrate，项目内只选一种 |
| Redis | go-redis |
| 精确小数 | decimal 库；金额、净值和收益计算禁止直接使用 `float64` 作为最终口径 |
| 外部 HTTP | 标准库 `net/http`，统一封装超时、重试、限流和观测 |
| 并发 | goroutine、channel、`errgroup`、带权信号量、`context` |
| 异步任务 | Asynq；简单定时入口使用 cron |
| Agent | Eino 负责模型、Tool Calling、流式与基础图编排 |
| Agent Runtime | 项目自研 Router、Planner、DAG Executor、HITL、Memory、Evidence、Eval |
| RAG | Elasticsearch BM25 + dense vector + RRF；本地内存实现用于测试 |
| 指标 | Prometheus client |
| 链路追踪 | OpenTelemetry |
| 日志 | `slog` 结构化日志 |
| 测试 | `testing`、`httptest`、Testcontainers、`go test -race`、Fuzz、Benchmark |

依赖使用明确版本并提交 `go.sum`。开发时使用团队当前稳定 Go 版本，不把实验性语言特性作为核心依赖。

## 4. 总体架构

```text
Web / App
   |
Gin REST + SSE
   |
Application Use Cases
   |--------------------------|
   |                          |
基金与组合业务             Agent Runtime
   |                    Router / Planner
Analytics Engine       DAG Executor / HITL
   |                    Tool Registry / Memory
   |                    Evidence / Evaluation
   |                          |
   |--------------------------|
              Ports
   |-----------|--------------|--------------|
MySQL       Redis         Data Provider   Knowledge Index
                              |                 |
                   基金/行情/资讯 API     Elasticsearch

Scheduler / Asynq Worker
   -> 数据同步
   -> 指标预计算
   -> 文档摄取
   -> 日报周报
   -> 风险通知
```

## 5. 推荐目录结构

```text
jijing-agent-go/
├─ cmd/
│  ├─ api/                    # HTTP 服务入口
│  ├─ worker/                 # Asynq Worker 入口
│  └─ migrate/                # 数据迁移入口（可选）
├─ internal/
│  ├─ config/                 # 配置加载与启动校验
│  ├─ transport/http/
│  │  ├─ handler/             # Gin Handler
│  │  ├─ middleware/          # Request ID、认证、恢复、日志、限流
│  │  ├─ dto/                 # HTTP 请求响应模型
│  │  └─ sse/                 # SSE 协议与断连处理
│  ├─ application/
│  │  ├─ fund/                # 基金查询与同步用例
│  │  ├─ analytics/           # 指标与比较用例
│  │  ├─ portfolio/           # 组合与持仓用例
│  │  ├─ agent/               # Agent 对外用例
│  │  └─ knowledge/           # 文档摄取与检索用例
│  ├─ domain/
│  │  ├─ fund/                # 基金、净值、持仓领域模型
│  │  ├─ analytics/           # 指标值、口径、算法版本
│  │  ├─ portfolio/           # 组合、仓位、风险规则
│  │  ├─ agent/               # Run、Task、Evidence、Approval
│  │  └─ knowledge/           # Document、Version、Chunk、Citation
│  ├─ analytics/              # 纯 Go 指标计算，不依赖 Gin/DB/LLM
│  ├─ provider/
│  │  ├─ contract/            # 外部数据契约
│  │  ├─ mock/                # 稳定测试数据
│  │  └─ http/                # 真实数据适配器
│  ├─ repository/
│  │  ├─ mysql/               # sqlc 查询和 Repository 实现
│  │  └─ redis/               # Cache Aside、锁、版本化 Key
│  ├─ agent/
│  │  ├─ model/               # 模型适配与配置
│  │  ├─ router/              # Direct / Parallel / Plan 模式选择
│  │  ├─ planner/             # 结构化计划生成与校验
│  │  ├─ executor/            # DAG、并发、超时、重试、取消
│  │  ├─ tool/                # 强类型 Tool 与 Registry
│  │  ├─ memory/              # 会话窗口与摘要
│  │  ├─ approval/            # HITL 静态策略
│  │  ├─ evidence/            # 证据验证与引用防伪造
│  │  ├─ prompt/              # 版本化 Prompt / Skill
│  │  └─ eval/                # 固定评测集与评分
│  ├─ knowledge/
│  │  ├─ parser/              # PDF/HTML/TXT 安全解析
│  │  ├─ splitter/            # 中文结构化切块
│  │  ├─ embedding/           # 批量向量化
│  │  ├─ index/               # Local / Elasticsearch 适配器
│  │  └─ retrieval/           # BM25、向量、RRF、上下文预算
│  ├─ scheduler/              # cron 注册与任务提交
│  └─ observability/          # slog、metrics、trace、审计
├─ db/
│  ├─ migrations/
│  ├─ queries/                # sqlc SQL
│  └─ sqlc.yaml
├─ configs/
├─ testdata/
├─ docs/
├─ deployments/
│  └─ docker-compose.yml
├─ go.mod
└─ README.md
```

## 6. 依赖规则

### 6.1 允许的方向

```text
transport -> application -> domain
application -> domain ports
repository/provider -> domain ports
agent tool -> application use case
scheduler -> application use case
```

### 6.2 禁止事项

- Handler 直接调用 sqlc、Redis 或 Provider。
- Domain 导入 Gin、sqlc、Redis、Eino 或 Elasticsearch Client。
- Tool 直接读数据库。
- Analytics 依赖 Agent 或 LLM。
- Provider 把第三方字段结构泄漏到 Domain。
- 使用全局可变 map 保存会话和业务状态。
- goroutine 脱离 `context` 生命周期无限运行。

## 7. 核心领域模型

### 7.1 基金与净值

```go
type Fund struct {
    Code       string
    Name       string
    Type       FundType
    Manager    string
    Source     DataSource
    DataTime   time.Time
    DataVersion string
}

type NAVPoint struct {
    FundCode      string
    TradingDate   time.Time
    UnitNAV       decimal.Decimal
    AccumulatedNAV decimal.Decimal
    AdjustedNAV   *decimal.Decimal
    Status        NAVStatus // CONFIRMED / ESTIMATED / REVISED
    Source        DataSource
    DataVersion   string
}
```

基金代码、日期区间、净值状态和口径使用强类型，不在业务代码中散落字符串。

### 7.2 指标值

```go
type MetricValue struct {
    Status           MetricStatus // AVAILABLE / UNAVAILABLE
    Value            *decimal.Decimal
    Reason           string
    AlgorithmVersion string
    DataVersion      string
}
```

数据不足、零波动、缺失交易日历都应显式表达，不能返回伪造的零值。

### 7.3 Agent Run 与任务

```go
type AgentRun struct {
    ID             string
    ConversationID string
    Mode           RunMode // DIRECT / PARALLEL / PLAN_EXECUTE
    Status         RunStatus
    PromptVersion  string
    Model          string
    StartedAt      time.Time
    CompletedAt    *time.Time
}

type AgentTask struct {
    ID           string
    RunID        string
    Type         TaskType
    Dependencies []string
    Status       TaskStatus
    RetryCount   int
    Timeout      time.Duration
}
```

状态转换必须集中在领域方法中校验，不能由各个 goroutine 随意修改。

## 8. 数据接入设计

### 8.1 Provider 端口

```go
type FundDataProvider interface {
    GetFund(ctx context.Context, code string) (fund.Fund, error)
    ListNAV(ctx context.Context, code string, from, to time.Time) ([]fund.NAVPoint, error)
    GetQuote(ctx context.Context, code string) (fund.Quote, error)
    ListHoldings(ctx context.Context, code string, reportDate time.Time) ([]fund.Holding, error)
    SearchNews(ctx context.Context, query NewsQuery) ([]NewsItem, error)
}
```

### 8.2 统一适配链

```text
ObservedProvider
  -> RateLimitedProvider
    -> RetryProvider
      -> CachedProvider
        -> PrimaryProvider / FallbackProvider
```

要求：

- HTTP Client 必须配置连接、响应头和总请求超时。
- 重试只用于明确的瞬时错误，使用指数退避和抖动。
- 按供应商设置 QPS、并发上限和熔断条件。
- 每次同步保存来源、请求批次、数据时间、哈希、数量与质量结果。
- 写库采用业务唯一键幂等 upsert。
- Provider 返回异常字段时隔离该批次，不能污染正式数据。

### 8.3 缓存策略

- 使用 Cache Aside。
- Key 包含数据版本、指标算法版本和查询口径。
- Redis 故障时降级到 MySQL，不能使查询整体不可用。
- 热点空值使用短 TTL，防止缓存穿透。
- 同一基金同步使用分布式锁或任务唯一键防止重复执行。

## 9. Analytics Engine

该包保持纯 Go，不依赖框架、数据库和模型。

必须实现：

- 日收益率；
- 区间累计收益率；
- 年化收益率；
- 样本年化波动率；
- 最大回撤、峰值日、谷值日和恢复日；
- 夏普比率；
- 上涨日比例与极值收益；
- 多基金公共日期区间对齐与稳定排名；
- 持仓重合度与组合集中度。

计算要求：

- 对输入按日期排序并去重。
- 明确使用单位净值、累计净值或复权净值。
- 最终结果使用 decimal；只有统计中间步骤确有必要时使用 `float64`，并在边界转换。
- 每种算法带 `algorithm_version`。
- 对已知向量写表驱动单测，并增加性质测试和 Fuzz Test。

## 10. Agent Runtime

### 10.1 哪些交给 Eino

- OpenAI 兼容模型适配；
- Tool Schema 与 Tool Calling；
- 流式模型输出；
- 基础 Graph/Compose 能力；
- Callback 扩展点。

### 10.2 哪些由项目自己实现

- 基金问题路由；
- Tool 白名单和权限；
- 执行轮次、Tool 次数、重复调用和总耗时限制；
- Direct / Parallel / Plan-and-Execute 模式选择；
- DAG 计划校验、持久化、执行和局部重试；
- HITL 审批；
- 记忆边界；
- 证据与引用验证；
- Agent 审计、评测与成本治理。

### 10.3 路由模式

| 模式 | 示例 | 执行方式 |
| --- | --- | --- |
| `DIRECT` | “000001 是什么基金” | 单 Tool 或固定回答 |
| `PARALLEL` | “比较 A、B、C 的回撤和波动率” | 无依赖 Tool 受限并发 |
| `PLAN_EXECUTE` | “生成我的组合月度风险报告” | Planner 生成 DAG，分批执行 |
| `CLARIFY` | “分析一下这个基金”但没有代码 | 追问必要参数 |
| `REFUSE` | 收益保证、确定性买卖要求 | 安全拒答 |

### 10.4 Tool 设计

首批只读 Tool：

```text
get_fund_profile
get_fund_nav
calculate_fund_metrics
compare_funds
compare_holdings
analyze_portfolio
search_fund_documents
search_market_news
```

Tool 统一返回：

```go
type ToolResult[T any] struct {
    Success      bool
    Data         T
    ErrorCode    string
    ErrorMessage string
    Evidence     []Evidence
    DataVersion  string
    Limitations  []string
}
```

Tool 错误作为结构化结果返回 Agent；不能 `panic`，也不能让模型把错误文本解释为业务事实。

### 10.5 并发执行器

要求：

- 一个 Tool 时直接执行，不额外创建 goroutine。
- 多 Tool 使用 `errgroup` + semaphore 限制全局并发。
- 再按 Provider 设置独立限流器。
- 单 Tool、单批次、整个 Run 分别设置超时。
- 父 `context` 取消时终止下游 HTTP、数据库、模型和 Tool。
- 返回结果与原 Tool Call ID 一一对应，不能因并发改变顺序。
- 对模型上下文中的 Tool 输出设置字符/Token 预算；大结果保存引用。
- 不允许 goroutine 泄漏，测试必须覆盖取消与超时。

### 10.6 Plan-and-Execute DAG

Planner 输出强类型 JSON，至少包含任务、类型、参数、依赖和预期结果。执行前必须：

- 校验任务类型与 Tool 白名单；
- 重新生成内部任务 ID；
- 两遍扫描建立依赖；
- 使用拓扑排序检测环；
- 限制任务总数、最大深度和并行宽度；
- 将计划与任务落库后才执行。

无依赖任务组成执行批次。失败任务可以按策略重试；依赖失败的任务标记 `SKIPPED`。只有在计划不再可执行时才调用 Replanner，已完成节点不得重复执行。

### 10.7 HITL

风险判断使用静态策略：

| 操作 | 处理 |
| --- | --- |
| 查询公开数据、计算指标 | 自动执行 |
| 修改持仓和成本 | 审批 |
| 修改风险阈值或通知目标 | 审批 |
| 导出含持仓信息的报告 | 审批 |
| 向第三方发送报告 | 审批 |
| 自动交易 | 禁止 |

审批结果支持：批准一次、会话内批准同类 Tool、修改参数后批准、拒绝并说明原因、跳过。审批记录与会话绑定；会话结束时清除临时放行状态。

### 10.8 Memory

- Redis 保存当前会话窗口和运行中状态，设置 TTL。
- MySQL 保存用户明确确认的长期偏好。
- 持仓、成本、净值等权威事实每次从业务 Use Case 获取。
- 历史消息超过窗口后生成摘要；摘要不能覆盖业务事实。
- 不同用户、会话和租户严格隔离。

## 11. RAG 与知识库

RAG 只用于非实时文档知识：基金公告、季报、招募说明书、费用规则、研究方法和用户笔记。实时净值、行情和确定性指标继续走数据 Tool。

摄取流程：

```text
上传
-> MIME/大小/安全检查
-> 原文件保存
-> 文本解析
-> 中文结构化切块
-> 批量 Embedding
-> BM25 + dense vector 建索引
-> 版本清单提交
-> AVAILABLE
```

检索流程：

```text
问题规范化
-> 权限过滤
-> BM25 召回
-> 向量召回
-> RRF 融合
-> 可选重排
-> 邻居块扩展
-> 上下文预算裁剪
-> 带页码证据返回
```

必须防止：

- 文档中的提示词注入改变系统规则；
- 模型生成不存在的文档 ID、页码和引用；
- 不同用户的私有文档越权检索；
- 文档版本变化后继续引用旧索引。

## 12. HTTP 与 SSE API

建议第一阶段 API：

```text
GET  /api/v1/funds/:code
GET  /api/v1/funds/:code/nav
GET  /api/v1/funds/:code/metrics
POST /api/v1/fund-comparisons

POST /api/v1/agent/conversations
POST /api/v1/agent/chat
POST /api/v1/agent/chat/stream
GET  /api/v1/agent/runs/:runId
POST /api/v1/agent/runs/:runId/cancel
POST /api/v1/agent/approvals/:approvalId/decision

POST /internal/v1/funds/:code/sync
POST /internal/v1/knowledge/documents
POST /internal/v1/knowledge/search/debug
```

SSE 事件协议：

```text
run.started
plan.created
task.started
task.completed
tool.started
tool.completed
approval.required
answer.delta
evidence.completed
run.completed
run.failed
run.cancelled
```

每个事件包含 `requestId`、`runId`、时间戳和序号。客户端断开时取消上游 `context`，并把未完成 Run 标记为 `CANCELLED`。

## 13. 数据库与审计

最少需要这些表：

```text
fund
fund_nav
fund_holding
fund_sync_record
fund_metric_snapshot
trading_calendar
watchlist
portfolio_position
agent_conversation
agent_message
agent_run
agent_task
agent_tool_call
agent_approval
analysis_evidence
knowledge_document
knowledge_document_version
knowledge_chunk_manifest
knowledge_ingestion_task
notification_rule
notification_record
```

工具参数和响应审计需要脱敏；不要记录 API Key、完整用户持仓或完整模型上下文。大对象保存摘要、哈希和受控引用。

## 14. 可观测性

### 14.1 日志

统一使用 `slog` JSON 输出：

```text
request_id / user_id / run_id / task_id / tool_call_id
provider / fund_code / data_version / algorithm_version
duration_ms / error_code / retry_count
```

### 14.2 Metrics

- Provider 请求数、错误率、P95、限流和熔断次数；
- 数据同步数量、拒绝记录数、数据新鲜度；
- Analytics 计算耗时与缓存命中率；
- Agent Run 数、成功率、模式分布、Tool 成功率；
- 首 Token 时间、总耗时、取消率、Token 和模型成本；
- RAG 召回耗时、引用通过率和拒答率。

### 14.3 Trace

一条 Trace 串联：

```text
HTTP -> Router -> Planner -> Task -> Tool -> Use Case
     -> MySQL/Redis/Provider/Model/Elasticsearch
```

## 15. 测试策略

### 15.1 Domain 与 Analytics

- 表驱动单元测试；
- 已知净值向量与预期指标；
- 边界日期、重复日期、缺失数据、零波动；
- Fuzz：解析、计划 JSON、Tool 参数、切块器；
- Benchmark：长净值序列、批量比较、RRF。

### 15.2 并发测试

- 所有测试至少运行一次 `go test -race ./...`；
- Tool 超时、Run 取消和 SSE 断连；
- 并发结果顺序；
- Provider QPS 和最大并发限制；
- 同步任务幂等；
- goroutine 泄漏检测。

### 15.3 Agent 评测

固定回归集至少覆盖：

- 问题路由正确率；
- Tool 选择和参数正确性；
- 数值与 Analytics 结果一致；
- 数据来源和证据完整；
- 数据不足时拒答；
- 收益保证与确定性仓位建议拦截；
- 伪造引用拦截；
- 越权读取组合和文档拦截；
- 复杂计划失败后的局部恢复。

评测分三层：不调用模型的纯单测、Mock 模型编排测试、显式开启的真实模型 Smoke Test。

## 16. 分阶段开发规划

### Phase 0：Go 工程基线

交付：

- 初始化 `go.mod`、目录、配置、依赖注入和优雅关闭；
- Gin、Request ID、统一响应、错误码、Recovery；
- MySQL Migration、sqlc、Redis、健康检查；
- MockProvider；
- `go test`、`go vet`、格式化和 CI。

完成标准：本地一条命令启动；无模型 Key 也可运行基金基础 API。

### Phase 1：真实数据底座

交付：

- 基金与净值模型；
- HTTP Provider 契约与一个真实适配器；
- MySQL Repository、幂等同步、数据质量校验；
- Redis Cache Aside；
- 重试、限流、熔断、同步审计；
- 定时增量同步。

完成标准：查询统一从 MySQL/Redis 读取；Provider 故障不直接击穿用户请求。

### Phase 2：指标与多基金比较

交付：

- 纯 Go Analytics Engine；
- 收益、波动、回撤、夏普和比较；
- 数据/算法版本；
- 指标快照和版本化缓存；
- 交易日历覆盖率；
- Fuzz、Benchmark 和已知向量测试。

完成标准：计算结果可复算，数据不足显式 `UNAVAILABLE`。

### Phase 3：受控单 Agent

交付：

- Eino 模型接入；
- Router、强类型 Tool、Tool Registry；
- 执行轮次、次数、重复调用和总超时限制；
- 会话 Memory；
- REST/SSE；
- Evidence、审计和基础评测；
- 模型未配置时安全关闭。

完成标准：模型只能通过只读 Tool 取得基金数据，回答携带真实证据。

### Phase 4：Agent Runtime

交付：

- Direct / Parallel / Plan-Execute 模式；
- 持久化 DAG、拓扑排序、环检测；
- 受限并发、批次超时、取消和局部重试；
- Replanner；
- HITL；
- 计划和任务 SSE 可视化事件；
- AgentOps 指标与成本统计。

完成标准：复杂报告可恢复、可取消、可审批、可审计。

### Phase 5：基金文档 RAG

交付：

- PDF/HTML/TXT 安全摄取；
- 文档与版本状态机；
- 中文结构化切块；
- 本地索引与 Elasticsearch 双适配；
- BM25 + dense + RRF；
- 页码级证据和引用验证；
- RAG 评测集和提示词注入测试。

完成标准：实时数据 Tool 与文档 RAG 正确路由，引用不可伪造。

### Phase 6：组合与主动助手

交付：

- 自选组合、真实持仓和风险画像；
- 行业暴露、持仓重合和组合集中度；
- 日报、周报与风险规则；
- 通知任务和 HITL；
- 用户数据权限与导出审计。

完成标准：系统从被动问答升级为可控的主动基金助手。

### Phase 7：生产化与简历收口

交付：

- 压测、容量模型、故障演练；
- Metrics、Trace、告警面板；
- 成本预算、多模型路由和 Prompt Cache；
- Docker 镜像、部署文档、演示数据与架构图；
- 完整测试报告和可量化结果。

完成标准：简历中的每个性能和质量数字都有测试报告支撑。

## 17. 推荐开发顺序

```text
先数据正确
-> 再指标正确
-> 再单 Agent Tool Calling
-> 再 DAG/HITL/并发 Runtime
-> 再 RAG
-> 最后多 Agent、主动通知和性能优化
```

不要同时开发所有模块。每个 Phase 都要有可运行 API、自动化测试、验收记录和 README 更新。

## 18. Go 学习重点

该项目应刻意训练这些 Go 能力：

- `context` 从 HTTP 贯穿数据库、Provider、Tool、模型和 SSE；
- interface 定义在使用方，基础设施实现端口；
- 错误包装、错误码和 `errors.Is/As`；
- goroutine 生命周期、channel 所有权和有界并发；
- `errgroup`、信号量、限流、超时和取消；
- `database/sql` 连接池与事务；
- 泛型只用于统一结果等明确场景；
- 表驱动测试、Fuzz、Benchmark、Race Detector；
- 避免 Java 式过度抽象，不为每个结构体机械创建 interface。

## 19. 简历表达模板

> 基金分析与组合风险 Agent｜Go、Gin、Eino、MySQL、Redis、Elasticsearch
>
> - 基于端口适配架构接入基金净值、持仓和行情数据，设计幂等同步、数据质量校验、Redis 版本化缓存及 Provider 限流降级机制。
> - 使用纯 Go 实现收益、波动率、最大回撤、夏普比率和多基金公平比较引擎，通过数据版本与算法版本保证结果可复算。
> - 构建受控 Tool Calling Agent，模型仅通过强类型只读 Tool 访问业务用例，并以 SSE 输出带数据来源、版本和证据编号的流式回答。
> - 自研 Plan-and-Execute DAG Runtime，支持拓扑校验、无依赖任务受限并发、分层超时、取消传播、局部重试、HITL 审批和执行审计。
> - 实现基金文档 BM25 + dense-vector 混合检索与 RRF 融合，通过页码引用校验、提示词注入防护和固定评测集降低幻觉与越权风险。

只有完成压测或评测后，才能补充“P95 降低多少”“Tool 选择正确率多少”等量化结果。

## 20. 第一阶段立即执行清单

1. 新建独立 Go 仓库或 `go-version` 目录，不与现有 Maven 模块混编。
2. 创建 `cmd/api`、`internal/domain`、`internal/application`、`internal/transport/http`。
3. 初始化 Gin、配置、Request ID、统一错误和优雅关闭。
4. 定义 `FundDataProvider` 与 `FundRepository` 端口。
5. 实现 MockProvider 和 `GET /api/v1/funds/:code`。
6. 引入 MySQL Migration、sqlc 与 Repository。
7. 添加 `go test ./...`、`go test -race ./...` 和 CI。
8. 第一条链路稳定后，再开始真实数据同步。

