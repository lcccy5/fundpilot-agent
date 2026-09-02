# Java 版基金 Agent V1～V6 开发大纲

## 1. 文档定位

这份路线不是从一个假想的空项目开始设计，而是基于当前仓库代码重新盘点后制定。

当前项目已经有真实基金公开数据、真实模型配置、基金分析、Spring AI Tool Calling、RAG、证据链、异步知识库任务和一个可运行的 Web 工作台。因此：

- **V1.0 是当前已经实现的版本，不是待开发版本。**
- **V2.0 不再重复“接入真实数据源、接入真实模型”。**它负责把已经能运行的真实能力收敛成稳定、可评测、可维护的研究产品。
- V3.0 才加入真正的用户账户、自选持久化和用户个人持仓。当前代码中催化研究工具里的 `Portfolio` 是“基金披露持仓”，不是“用户购买的基金组合”。
- V4.0 在现有单 Agent Tool Calling 上增加 Direct、ReAct、Plan-and-Execute 三种执行模式和持久化长任务。
- V5.0 基于 V4 的任务图增加主动提醒和有限多 Agent，不构建自由聊天式 Agent 群。
- V6.0 最后完成生产化；没有容量和故障数据之前继续使用模块化单体，不提前拆微服务。

项目技术主线保持：Java 21、Spring Boot 3.5、Spring AI 1.1、MySQL、MyBatis-Plus、Redis、Redisson、Resilience4j、Elasticsearch、Micrometer，以及现有 Next/React 前端。

## 2. 当前仓库盘点结果

### 2.1 已有模块

| 模块 | 当前职责 | 当前状态 |
| --- | --- | --- |
| `fund-domain` | 基金、净值、Repository/Provider Port | 已实现，保持纯领域层 |
| `fund-analytics` | 收益和风险指标计算 | 已实现，纯 Java，可单测 |
| `fund-application` | 查询、同步、比较、快照等 Use Case | 已实现 |
| `fund-knowledge` | 文档状态机、切块、RRF、检索与上下文预算 | 已实现，纯 Java 核心 |
| `fund-infrastructure` | MySQL、Redis、ES、文档解析、Embedding、数据 Provider | 已实现主要适配器 |
| `fund-agent-runtime` | Spring AI、工具路由、记忆、安全、证据和审计 | 已实现单 Agent；部分新增研究工具需要下沉重构 |
| `fund-interface` | 基金、指标、比较、Agent 和知识库 REST/SSE API | 已实现 |
| `fund-scheduler` | 净值同步、指标快照、知识摄取 Worker | 已实现 |
| `fund-test-support` | RAG 评测数据集与指标 Runner | 已有框架，真实标注集不足 |
| `fund-observability` | 预留可观测模块 | 目前基本为空，指标散落在其他模块 |
| `fund-bootstrap` | Spring Boot 装配、配置、Flyway V1～V8 | 已实现 |
| `fund-web` | FundPilot Web 工作台 | 已有可运行页面，但仍是第一版演示前端 |

### 2.2 当前自动化验证事实

在 2026-08-27 对仓库执行完整离线 `mvn test`：

- Maven 12 个 Reactor 项目全部构建成功。
- 53 项测试实际执行并通过。
- 7 项测试被跳过：1 项真实 Elasticsearch、4 项真实 MySQL 迁移、1 项真实 Embedding、1 项真实 Chat Model。
- 被跳过的原因是这些测试被设计为显式启用，不代表对应代码不存在，但说明默认构建并未证明真实外部环境持续可用。

### 2.3 当前需要正视的技术债

- `FundRealtimeQuoteTool`、`SectorOutlookTool`、`FundCatalystResearchTool` 已经访问真实公开数据，但直接在 `fund-agent-runtime` 中创建 `RestClient` 并硬编码外部地址，没有完整走 Domain Port → Application Use Case → Infrastructure Adapter。
- 东方财富 Provider 已用于真实基础资料和净值，但代码注释明确限定为个人学习/演示；缺少正式授权数据源和多源一致性机制。
- “实时涨跌”实际是关联场内 ETF 的代理行情，不是场外基金当日官方净值，现有工具已经给出免责声明，后续必须在所有 UI/API 中继续保持这个口径。
- 真实 Chat/Embedding 配置已经存在，但真实模型 Smoke Test 默认跳过；模型升级没有稳定的质量回归门禁。
- RAG 已有评测 Runner，但仓库里只有示例数据集，没有足够的真实基金文档标注集。
- 前端自选基金只保存在 React 内存中，刷新即丢失；知识库页面是占位，比较与完整指标能力也没有完整呈现在 UI。
- 前端净值查询日期写死，尚未按当前日期和用户选择动态计算。
- `fund-observability` 没有实质代码；当前虽然已有 Actuator、Prometheus 和局部 Micrometer 指标，但还没有统一可观测体系。
- 没有 Spring Security、用户表、RBAC、用户持仓、交易流水和数据隔离。
- 当前 Agent 是关键词预路由 + Spring AI Tool Calling，不是完整 ReAct Runtime，也没有 Plan、Task、Checkpoint、审批和断点恢复。

## 3. V1.0：可信基金研究 Agent 基线（当前版本）

### 3.1 版本目标

完成一个能够使用真实公开数据查询基金、进行确定性指标计算、调用大模型解释结果，并对关键事实提供证据的学习型基金研究助手。

### 3.2 当前已经实现

#### A. 基金数据与持久化

- `EastMoneyFundDataProvider` 获取真实基金基础信息、基金经理和历史净值。
- 保留 Mock Provider 和标准 HTTP Provider，支持测试与替换数据源。
- MySQL 保存基金、历史净值、同步审计、交易日历和指标快照。
- 幂等 upsert、数据修订版本、缓存版本 Key、同步锁和缓存失效。
- Redis Cache Aside；Redis 故障时退化为数据库/Provider 查询。
- 本地锁与 Redisson 分布式锁、定时增量同步、Resilience4j 重试/限流/熔断。

#### B. 确定性基金分析

- 累计收益、年化收益、年化波动率、最大回撤、峰谷恢复日、夏普比率、上涨日占比和极值收益。
- 单位净值、累计净值、复权净值口径。
- 确认、估算、修正净值状态。
- 数据覆盖率、样本不足、零波动显式不可用，不用数值 0 掩盖问题。
- 2～10 只基金按照公共净值区间进行公平比较和稳定排名。
- 纯 Java 计算模块，不依赖数据库、Spring 或大模型。

#### C. 真实市场研究工具

- `fund_realtime_quote`：查询关联 ETF 的真实盘中行情，并明确它只是场外基金的代理指标。
- `analyze_sector_outlook`：选择代表 ETF，使用实时价格和近 90 个交易日日线生成基准/乐观/悲观情景。
- `research_fund_catalysts`：解析 ETF 或 ETF 联接基金，优先读取日频 PCF，无法获取时使用披露持仓；继续映射产业链、查询上市公司公告并计算持仓影响。
- 催化研究内部已经形成“持仓 → 产业链 → 公告 → 影响评估”四步链，并生成分步骤 Evidence。

#### D. 单 Agent 与可信回答

- Spring AI `ChatClient` 接入 OpenAI 兼容 Chat Model 和 Embedding Model。
- 基金资料、历史净值、指标、基金比较、知识文档、实时行情、板块情景和催化研究工具。
- 关键词预路由减少无关工具暴露，已有 50 条固定路由样例。
- 单次工具次数、重复调用、工具超时、运行超时和输入长度限制。
- MySQL 会话、消息、Agent Run、Tool Call 审计。
- Token 预算消息窗口和带 Evidence、TTL 的 Fact Card 跨轮记忆。
- 输入/输出安全策略，拦截提示词窃取、收益保证和确定性仓位建议。
- Claim 级证据类型检查、引用修复和证据不足拒答。
- REST 与真实增量 SSE；输出运行、工具执行、证据校验、完成和失败事件。

#### E. 知识库 RAG

- PDF、HTML、Text 解析和本地原文存储。
- 中文结构化切块、页码保留、Embedding 批处理。
- 本地索引以及 Elasticsearch BM25 + dense-vector kNN。
- RRF 融合、可插拔 Reranker、上下文 Token 预算和相邻 Chunk 合并。
- 文档/版本/摄取任务状态机，MySQL 租约、心跳、失败重试和 Checkpoint 恢复。
- ES 物理索引重建、读写别名原子切换和回滚。
- 文档提示词注入、错误基金元数据和伪造引用安全样例。

#### F. Web 工作台

- 基金代码查询与真实数据同步。
- 基金资料、单位净值走势图和基础数据洞察。
- SSE Agent 对话、工具执行进度和富文本回答展示。
- 本地临时自选列表和知识库入口占位。

### 3.3 V1 可以与不能宣称的内容

可以宣称：

- 已构建真实公开数据驱动的基金 Tool Agent。
- 已实现确定性金融计算、RAG 混合检索、证据化回答和异步知识库治理。
- 已接入真实大模型和 Embedding 的配置及可选 Smoke Test。
- 已构建可运行的 Java 后端和 Web 演示工作台。

不能宣称：

- 生产授权金融数据平台。
- 场外基金官方盘中实时净值。
- 已完成大规模真实 RAG/Agent 质量评测。
- 已有用户个人持仓与个性化投资助手。
- 已实现持久化 Plan-and-Execute、多 Agent 或生产高可用。

### 3.4 V1 冻结验收

- 当前 53 项默认测试持续通过。
- Mock 模式无外部密钥可以启动。
- EastMoney 模式能够查询指定基金真实资料和历史净值。
- 真实模型启用后，基本资料、指标、实时代理行情和催化问题能够通过 SSE 返回带 Evidence 的回答。
- README 明确数据来源、实时口径和非投资建议边界。

V1 完成后不再继续向 `fund-agent-runtime` 塞新的 HTTP 抓取逻辑；新增数据能力从 V2 开始先建立 Port 和 Use Case。

## 4. V2.0：真实能力工程收口与质量评测

### 4.1 为什么还需要 V2

V2 不是证明“能访问一次真实接口”，而是证明：外部接口变化、模型波动、数据冲突、用户刷新页面或服务重启时，系统仍然可解释、可测试、可降级。

### 4.2 V2-S1：重构真实研究工具

基于现有代码做迁移，不重写功能：

1. 在 `fund-domain` 定义 `RealtimeMarketDataProvider`、`FundHoldingProvider`、`MarketEventProvider`、`IndustryProfileProvider` 等 Port。
2. 在 `fund-application` 新增实时行情、板块情景和催化研究 Use Case。
3. 将东方财富、腾讯行情、深交所 PCF、上交所 PCF、公司公告查询移入 `fund-infrastructure` Adapter。
4. `FundRealtimeQuoteTool`、`SectorOutlookTool`、`FundCatalystResearchTool` 只负责参数 Schema、调用 Use Case 和包装 Tool Evidence。
5. 把当前硬编码 URL、User-Agent、连接/读取超时、重试次数全部移入配置。
6. 统一接入 Resilience4j、Micrometer、日志脱敏、错误码和 Provider 契约测试。
7. 对同一次复杂催化研究设置总 Deadline，避免每个子请求分别耗尽超时预算。

完成后的调用链统一为：

```text
Agent Tool → Application Use Case → Domain Port → Infrastructure Adapter → 外部数据源
```

### 4.3 V2-S2：数据来源与质量治理

- 保留东方财富作为学习环境真实主源，不再把它描述成“尚未接入”。
- 为净值、基金资料、ETF 行情、PCF、披露持仓和公告分别定义来源等级、新鲜度 SLA 和允许用途。
- 数据记录至少携带 `sourceName/sourceUri/sourceUpdatedAt/collectedAt/dataVersion/qualityStatus`。
- 对官方净值、盘中估算、ETF 代理行情建立不可混用的类型或枚举，而不只依赖提示词免责声明。
- 增加响应字段漂移检测；第三方接口缺字段时生成数据质量事件，而不是静默返回不完整结果。
- 接入第二来源只用于关键字段交叉验证和故障降级；如果没有合规来源，不为了“多数据源”强行增加不稳定爬虫。
- 保存冲突记录，由规则选择可信值；原始值不可静默覆盖。

建议新增：

```text
fund-domain/application/infrastructure 内的 provider-governance 包
data_provider_status
data_quality_issue
provider_observation
provider_reconciliation_record
```

### 4.4 V2-S3：真实模型和 RAG 的可重复验收

真实模型已经接入，本阶段只补质量证明：

- 将现有真实测试迁移为 JUnit Tag + Maven Failsafe 管理的显式 `real-env` Profile，并禁止关键测试静默跳过。
- 为真实环境测试增加脱敏报告，绝不在日志或报告中写 API Key。
- 分别建立 Routing、Tool Contract、安全和 RAG 数据集；真实基金文档 RAG 标注集不少于 100 条。
- 导入真实基金季报、年报、招募说明书和公告，不能只使用合成文本。
- 输出 Tool 路由正确率、任务完成率、Recall@K、MRR、nDCG、引用准确率、证据完整率、拒答正确率、P95 延迟和平均成本。
- 模型、Prompt、Tool Schema、切块或 Embedding 版本变化时，自动生成基线差异报告。
- 阈值由第一批标注结果校准；不能先随意写一个高阈值，再通过降低题目难度达标。

### 4.5 V2-S4：Web 研究台闭环

在现有 `fund-web` 上完成，而不是另建前端：

- 去掉写死的净值起止日期，支持 1M/3M/6M/1Y/3Y/自定义区间。
- 接入后端指标 API，展示累计收益、波动率、最大回撤、夏普比率及不可用原因。
- 增加 2～10 只基金比较页面，保证比较口径和公共区间可见。
- 增加 Knowledge 管理页面：上传文档、查看版本、观察摄取步骤、失败重试、检索 Debug、索引重建/切换/回滚。
- Agent 回答增加 Evidence 抽屉，展示来源、数据日期、口径、文档页码和限制，而不只是显示“证据”徽标。
- 正确处理 SSE 取消和断线，客户端主动取消不在服务端打印成高等级未知异常。
- 当前“我的自选”继续标明为本地临时功能，持久化放在 V3，避免 V2 偷做半套用户系统。

### 4.6 V2-S5：测试、可观测与安全收口

- 为三个新增真实研究工具补足 HTTP 契约测试、超时、空响应、字段漂移和部分来源失败测试。
- 为 Scheduler、Knowledge Worker 和更多 Controller 增加测试；当前这些区域覆盖不足。
- 让 `fund-observability` 承载统一指标命名、Tag 约束和观测约定。
- 增加 Provider 成功率/延迟、新鲜度、数据冲突、Agent Run、Tool、RAG 零命中、引用拦截、Token 和费用 Dashboard。
- CI 默认执行纯本地测试；受保护环境按计划执行真实环境测试，不要求普通 Pull Request 暴露密钥。
- 私密配置只作为本机忽略文件存在；正式运行使用环境变量或 Secret Manager，并执行密钥轮换和泄露扫描。

### 4.7 V2 验收标准

- 三个真实研究 Tool 不再直接创建外部 HTTP Client，Agent Runtime 中没有供应商解析逻辑。
- 东方财富或腾讯单个接口异常时，用户得到明确降级结果，Run 和 Provider 指标可定位原因。
- 所有实时类结果明确区分官方净值、估算值和 ETF 代理行情。
- 100 条以上真实评测集一条命令可运行，并保存可对比报告。
- Web 能完成“查询 → 指标 → 比较 → Agent → 查看证据 → 管理知识文档”的完整演示。
- 默认 Maven 测试保持全绿，真实环境 Profile 在受控环境中完成 Chat、Embedding、ES、MySQL 和外部 Provider 验收。

### 4.8 V2 简历表达

“在已有真实基金数据和 Spring AI Tool Agent 基础上，将多站点研究能力重构为 Port/Use Case/Adapter，建立数据口径治理、外部 Provider 降级以及 Agent/RAG 自动评测门禁，形成可复现的可信基金研究工作台。”

## 5. V3.0：个人基金账户、持仓与组合分析

详细的模块落点、V10～V14 迁移、认证安全、流水重建、XIRR、个性化 Tool、Web 和验收步骤见 [Java 版 V3 多用户个人组合与个性化 Agent 开发指引](10-Java版V3多用户个人组合与个性化Agent开发指引.md)。

代码同步状态：V10～V14、认证与 Agent 用户归属、自选、组合流水、持仓/XIRR 及 5 个个性化 Tool 已落地。进入 V4 前仍应按 V3 完成定义核对导入、风险画像、Web 闭环、跨用户安全和真实环境验收，而不能只以迁移文件存在作为完成依据。

### 5.1 V3 在现有项目上补什么

当前 `fund-web` 的“我的自选”只是 React 内存数组；当前催化工具的 `Portfolio` 是某只基金披露的股票组合。V3 新增的是用户自己的数据：

```text
用户买了哪些基金
→ 每笔申购/赎回/分红是什么
→ 当前持仓和成本是多少
→ 用户真实收益与组合风险是什么
→ Agent 如何基于这些事实个性化回答
```

### 5.2 V3-S1：用户与权限

- 引入 Spring Security。
- 用户注册、登录、密码哈希、JWT Access Token、Refresh Token 轮换和退出失效。
- USER、ANALYST、ADMIN RBAC；`/internal/**` 管理 API 不再裸露。
- 会话、Agent Run、自选、组合、报告全部绑定 `userId`。
- Controller 做身份入口校验，Application 做资源所有权校验，Repository 查询必须带用户条件。
- 增加跨用户越权集成测试，防止通过 conversationId、portfolioId 或 runId 读取他人数据。

### 5.3 V3-S2：自选基金持久化

- 把前端内存 `watch` 迁移为后端 `WatchlistGroup/WatchlistItem`。
- 支持分组、标签、备注、添加时间和排序。
- 用户登录后跨设备保留；未登录用户可以保留临时本地自选，登录后显式合并。
- Agent 新增只读 `get_my_watchlist` Tool。

### 5.4 V3-S3：交易流水与持仓重建

- 领域对象：`UserPortfolio`、`FundTransaction`、`FundPosition`、`CashFlow`。
- 支持申购、赎回、现金分红、红利再投资、基金转换和手续费。
- 交易流水是事实来源，当前持仓是可重建投影；禁止只维护一个可随意修改的持仓数量。
- CSV/Excel 模板导入经过预校验、幂等键和整批事务。
- 第一版不接券商账户、不自动下单，避免授权、安全和合规范围失控。

V2 使用 V9 Provider 治理迁移后，V3 建议从 V10 开始增加：

```text
user_account / role / user_role / refresh_token
watchlist_group / watchlist_item
user_portfolio / fund_transaction / fund_position_snapshot
risk_profile / user_audit_log
```

### 5.5 V3-S4：用户真实收益与组合风险

- 区分基金产品净值涨幅和用户资金加权收益。
- 计算持仓成本、已实现/未实现收益、总资产曲线、XIRR 或资金加权收益。
- 组合波动率、最大回撤、基金相关性、集中度、基金持仓重合度和行业/主题暴露。
- 缺少基金底层持仓时明确降低组合暴露覆盖率。
- 风险问卷生成可解释画像；画像是风险提示上下文，不是确定性买卖指令。

### 5.6 V3-S5：个性化 Agent

新增只读工具：

```text
get_my_watchlist
get_my_portfolio
calculate_my_return
analyze_my_portfolio_risk
compare_watchlist_funds
```

- 工具从认证上下文取 `userId`，不允许模型在参数里指定任意用户 ID。
- 用户持仓快照、净值日期、算法版本和计算口径进入 Evidence。
- 长期偏好只有经用户明确保存才能成为事实；模型推测不能直接写入风险画像。

### 5.7 V3 验收标准

- 两个测试用户之间的自选、组合、会话和 Agent Run 完全隔离。
- 同一组交易流水重复重放得到相同持仓、成本和收益；重复导入不产生重复交易。
- 组合结果能够与固定 Excel 样例对账。
- 用户能够在 Web 完成注册、登录、自选、流水导入、组合查看和个性化 Agent 提问。
- 对“我应该买/卖多少”等请求仍执行安全策略，不提供自动交易和收益承诺。

### 5.8 V3 简历表达

“基于 Spring Security 和交易流水重建模型实现多用户基金组合系统，将用户真实收益、组合风险和持仓暴露作为带版本 Evidence 注入 Agent，同时通过资源所有权校验防止跨用户工具越权。”

## 6. V4.0：混合 Agent Runtime 2.0

详细的四模式路由、受限 ReAct、V15～V18、PlanValidator、DAG/Lease/Checkpoint、HITL、事件回放和验收步骤见 [Java 版 V4 混合 Agent Runtime 2.0 开发指引](11-Java版V4混合AgentRuntime2.0开发指引.md)。

### 6.1 在现有 Agent 上如何演进

不替换 Spring AI。继续使用它负责模型调用、Tool Callback、Chat Memory 和流式响应；项目自研执行策略、持久化状态、审批、证据传播和恢复。

统一入口先选择执行模式：

| 模式 | 使用条件 | 示例 |
| --- | --- | --- |
| Direct/RAG | 不需要工具或只需知识检索 | “最大回撤是什么意思？” |
| Deterministic Tool | 工具明确且 1～2 步 | “查询 000001 最新资料” |
| ReAct | 需要边查边判断，通常 2～5 个工具，短时可重跑 | “000001 最近为什么下跌？” |
| Plan-and-Execute | 多目标、多基金、可并行、长时、需进度/恢复/审批 | “比较五只基金并结合我的组合生成报告” |

### 6.2 V4-S1：Execution Mode Router

- 先使用确定性特征：基金数量、意图数量、预计工具数、是否要求报告、是否涉及用户组合、是否需要后台执行。
- 简单请求继续使用现有 `FundToolRouter`，不为每个问题增加 Planner 成本。
- 模型只对边界请求提供分类建议；权限、审批和后台任务要求由规则强制决定。
- 每次 Run 保存选中的模式、规则版本和原因，便于评测路由是否合理。

### 6.3 V4-S2：受限 ReAct

- 执行“观察结果 → 选择下一工具”的短循环。
- 复用当前工具次数、重复调用、单 Tool 超时、总超时和 Evidence Trace。
- 增加结构化 Observation 摘要，不把完整原始响应无限塞回上下文。
- 设置最大轮次、最大 Token 和停止原因；连续无新 Evidence 时终止。
- 适合探索性研究，不写持久化 DAG；失败时整个请求可以安全重跑。

### 6.4 V4-S3：Plan-and-Execute

Planner 只输出符合 JSON Schema 的计划：

```text
Plan
├─ goal / inputSnapshot / budget / deadline
└─ Task[]
   ├─ taskId / taskType / typedInput
   ├─ dependencies / retryPolicy / approvalPolicy
   └─ expectedOutput / evidenceRequirement
```

- `PlanValidator` 校验任务白名单、DAG 无环、用户权限、参数、预算、截止时间和审批要求。
- 持久化 `agent_plan`、`agent_task`、`agent_task_dependency`、`agent_checkpoint`、`agent_event`、`agent_approval`。
- Executor 只执行依赖已经完成的 READY Task；独立查询可以有限并行。
- 每个 Task 保存状态、输入哈希、输出引用、Evidence、尝试次数、错误和耗时。
- 写副作用必须有幂等键；进程终止后从最近 Checkpoint 恢复。
- SSE 输出计划生成、步骤开始/完成、等待审批、重试、Replan 和最终完成事件；支持按事件序号恢复进度。

### 6.5 V4-S4：Replan、审批与验证

- 只有数据为空、标的歧义、Provider 不可用或原计划缺少必要步骤时允许 Replan。
- 最多 Replan 1～2 次，新计划再次通过 Validator，并保留版本差异。
- 批量导入、外部通知、报告发布和高成本任务进入 `WAITING_APPROVAL`。
- 审批绑定动作摘要、参数哈希、用户、过期时间和一次性令牌；参数变化后重新审批。
- Verifier 检查任务完整性、基金比较区间、净值口径、数据新鲜度和 Evidence 覆盖。
- Writer 只使用通过验证的 Task 输出生成最终报告。

### 6.6 V4-S5：记忆与评测

- 继续使用现有 MySQL Message + Fact Card；Redis 仅作为热数据，不成为事实唯一来源。
- 增加用户可查看/修改/删除的长期偏好，记录来源和确认时间。
- 扩展评测集，分别统计 Direct、Tool、ReAct、Plan-and-Execute 的路由准确率、完成率、无效调用率、恢复成功率、P95 和费用。

### 6.7 V4 验收标准

- 简单查询不会错误进入昂贵的 Plan 模式。
- 探索性问题能够在受限 ReAct 中停止，不重复调用同一工具。
- “比较三只基金、分析用户组合并生成报告”形成可查看的 DAG，独立节点并行执行。
- 任一 Task 完成后杀死进程，重启能够继续，已成功幂等任务不重复产生副作用。
- 未审批步骤不能执行，审批后参数被修改必须失效。
- 最终结论可追溯到 Plan → Task → Tool Call → Evidence。

### 6.8 V4 简历表达

“在 Spring AI 之上设计混合 Agent Runtime，以复杂度路由选择 Tool Calling、受限 ReAct 和持久化 Plan-and-Execute，通过 DAG 调度、Checkpoint、HITL 和 Evidence 传播支持可恢复、可审计的基金研究长任务。”

## 7. V5.0：主动研究与有限多 Agent

详细的 Transactional Outbox、V19～V23、通知规则、固定角色、Tool ACL、结构化 Artifact、A/B 评测和 MCP 治理见 [Java 版 V5 主动研究与有限多 Agent 开发指引](12-Java版V5主动研究与有限多Agent开发指引.md)。

### 7.1 V5 的边界

多 Agent 不用于所有问答。普通查询仍走 V4 的 Direct/Tool/ReAct；只有研究报告、组合月报、重大事件影响等确实需要角色分工的异步任务才启动多 Agent。

### 7.2 V5-S1：事件驱动的主动助手

- 领域事件：净值已更新、组合回撤越线、持仓集中度变化、文档新版本、基金经理变化、重仓股重大公告。
- 使用 Transactional Outbox 保证业务提交和事件发布一致。
- 第一阶段使用 MySQL Outbox Poller 即可；确有吞吐或跨服务需求后再接 Kafka/RabbitMQ。
- 消费者具有幂等键、指数退避、死信、回放和延迟监控。
- 用户配置规则、阈值、冷却时间、免打扰时段和通知渠道。
- 生成日报/周报/月报，报告中的每项事实附日期、口径和 Evidence。

建议新增：

```text
outbox_event / event_consumption
notification_rule / notification_record
report_job / report_artifact / report_version
```

### 7.3 V5-S2：有限多 Agent 架构

复用 V4 的 Plan、Task、Checkpoint、Approval 和 Evidence，不建立第二套编排引擎：

```text
Supervisor
   ├─ Data Researcher：基金、净值、文档、持仓披露、公告
   ├─ Portfolio Analyst：用户持仓、收益、重合度、暴露
   ├─ Risk Analyst：确定性风险计算和压力情景
   └─ Verifier：口径、时效、冲突和证据校验
             ↓
           Writer：只使用已验证事实生成报告
```

角色约束：

- 角色集合固定，单次 Run 最多启用 3～5 个角色。
- Agent 之间通过数据库任务和结构化 Artifact 通信，不自由互聊。
- 每个角色拥有独立 Prompt、输入 Schema 和 Tool ACL。
- Data Researcher 不能读取用户账户；Portfolio Analyst 只能读取当前用户；Writer 默认不能访问外部数据源。
- Verifier 可以退回缺证据 Task，但不能编造新事实。
- Supervisor 控制最多 20 个 Task、最多 2 次 Replan、最多 1 次验证退回、总执行时间、模型次数、Tool 次数和费用。
- 只有多 Agent 相比单 Agent 评测基线有显著质量收益时，才对该场景启用。

### 7.4 V5-S3：MCP 作为适配协议

- 将基金资料、指标、文档检索和用户授权后的组合只读分析封装为 MCP Server。
- MCP 只是工具接入层，不替代 Application Use Case、权限和 Evidence 模型。
- 外部 MCP 内容先转为统一 Evidence，再进入安全与引用校验。
- 默认不暴露交易或任意写 Tool；通知、导出等操作继续经过审批。

### 7.5 V5 验收标准

- 服务重启后 Outbox 事件不丢失，重复消费不产生重复通知。
- 相同事件在冷却期内最多生成一次用户可见提醒。
- 周报能够异步执行、暂停/恢复、人工预览和版本化发布。
- 多 Agent 报告中的每段关键结论能够定位到负责角色、Task 和 Evidence。
- 普通查询不会启动多 Agent；复杂报告超过预算会降级或终止。
- 外部 MCP Tool 经过认证、授权、限流、超时和审计。

### 7.6 V5 简历表达

“基于 Outbox 和持久化任务图构建主动式基金监控，并以固定角色、Tool ACL、共享 Artifact 和统一 Evidence 实现受预算约束的多 Agent 研究报告，避免自由对话式多 Agent 的不可控成本与幻觉传播。”

## 8. V6.0：生产化与平台治理

### 8.1 版本目标

把前五版形成的产品能力放入可持续运行环境，解决部署、容量、观测、安全、灾备和交付问题，而不是单纯增加中间件。

### 8.2 V6-S1：可观测与 SLO

- 在现有 Micrometer/Prometheus 基础上引入 OpenTelemetry Trace。
- 将 HTTP → Agent Run → Plan/Task → Tool → Provider → SQL/Redis/ES/消息串成链路。
- 日志统一携带 `requestId/userId/runId/planId/taskId/evidenceId`，敏感字段脱敏。
- 定义 API 可用性、P95、数据新鲜度、任务成功率、引用完整率、通知延迟和模型成本 SLO。
- 每个告警有明确 Runbook 和负责人，不只创建 Dashboard。

### 8.3 V6-S2：安全治理

- 密钥进入 Secret Manager，禁止出现在源码、镜像、日志和前端 Bundle。
- 接口限流、安全 Header、CORS 白名单、上传文件病毒/类型/大小检查。
- 依赖漏洞扫描、SBOM、镜像签名和许可证检查。
- 用户数据加密、导出审计、数据保留和删除流程。
- Prompt、Tool 参数、RAG 文档、MCP 内容和报告导出使用统一安全策略。

### 8.4 V6-S3：部署与容量

- 构建可复现后端和前端容器镜像。
- 使用 Kubernetes Deployment/Job、Readiness/Liveness、HPA、PDB 和优雅停机。
- 先压测查询、SSE、Agent、Embedding、摄取 Worker 和报告任务，再决定拆分。
- 默认保留模块化单体；只有需要独立扩缩容或隔离故障域时，拆出 Knowledge Worker、Agent Worker 或 Notification Worker。
- MySQL、Redis、ES 使用生产集群或托管服务，并验证连接池与背压。

### 8.5 V6-S4：持续交付与灾备

- CI：单测、架构测试、契约测试、集成测试、前端检查、RAG/Agent 评测、安全扫描。
- CD：环境配置校验、Flyway 前向兼容、Feature Flag、灰度、指标门禁和自动回滚。
- 备份 MySQL、原始文档、报告 Artifact 和配置；ES 索引可从原文和元数据重建。
- 定期执行恢复演练，记录真实 RPO/RTO。
- 演练 Provider 中断、模型超时、Redis/ES 故障、消息积压和实例滚动退出。

### 8.6 拆服务的准入条件

只有满足以下条件之一才拆对应模块：

- 压测证明必须独立扩缩容。
- 发布频率和故障域明显不同。
- 已有稳定 API、数据所有权和团队边界。
- 拆分收益高于网络、分布式事务和运维成本。

### 8.7 V6 验收标准

- 灰度发布期间核心查询不中断，已受理长任务可恢复。
- 能从 Trace 定位一次慢 Agent Run 的具体 Task、Tool 或 Provider。
- 备份在隔离环境完成恢复，RPO/RTO 有实测数据。
- 高危漏洞、明文密钥、跨用户访问和未授权 Tool 无法通过发布门禁。
- 容量报告能够解释为什么某模块保留单体或被拆出，而不是凭经验决定。

### 8.8 V6 简历表达

“将基金 Agent 工作台生产化，基于 OpenTelemetry 与 SLO 建立端到端治理，通过容量驱动的服务拆分、Kubernetes 弹性、灰度发布、密钥治理和恢复演练保障系统可靠运行。”

## 9. 六个版本之间的真实依赖

```text
V1 真实公开数据 + 指标 + 单 Agent + RAG + Web Demo（当前）
 │
 └─ V2 分层重构 + 数据治理 + 真实评测 + Web 闭环
     │
     └─ V3 用户 + 自选 + 交易流水 + 组合分析
         │
         └─ V4 Direct/ReAct/Plan-and-Execute + 恢复/审批
             │
             └─ V5 主动事件 + 有限多 Agent + MCP
                 │
                 └─ V6 可观测 + 安全 + 高可用 + 持续交付
```

不能跳过的依赖：

- V2 的数据口径和评测是 V4/V5 Agent 可信性的基础。
- V3 的用户与组合模型是“我的持仓分析”和主动组合提醒的基础。
- V4 的持久化任务图是 V5 多 Agent 和周期报告的基础。
- V6 的生产化可以提前逐步做，但不应提前以微服务为目标。

## 10. 从当前代码开始的 V2 实施清单

按以下顺序开发，避免同时铺开：

### 第 1 个迭代：研究工具分层

- 提取四类 Domain Port。
- 把三个真实研究 Tool 的 HTTP 与解析代码迁入 Infrastructure。
- Application Use Case 负责四步催化链编排。
- 补 Provider 契约和故障测试。

完成定义：Agent Tool 中没有真实站点 URL 和响应字段解析。

### 第 2 个迭代：口径和数据质量

- 统一 Evidence 来源字段。
- 实现官方净值/估算/ETF 代理类型隔离。
- Provider 健康度、字段漂移和冲突记录。
- 前端展示数据时间、口径与限制。

完成定义：任何“今天涨跌”回答都能看出它到底是什么数据。

### 第 3 个迭代：真实评测集

- 收集并导入真实基金文档。
- 制作 100 条以上标注问题。
- 扩展现有 `fund-test-support` 报告。
- 建立模型/Prompt/Tool/RAG 版本对比。

完成定义：模型升级前能自动判断质量是否退步。

### 第 4 个迭代：Web 功能闭环

- 动态日期区间。
- 指标和多基金比较。
- Evidence 详情抽屉。
- 知识库上传、任务、检索和索引治理页面。
- SSE 取消与错误体验修正。

完成定义：核心后端能力不再只能用 Postman 演示。

### 第 5 个迭代：运行与交付门禁

- 补 Scheduler、Worker、Controller 测试。
- 统一 Micrometer 指标和 Dashboard。
- 默认 CI 与 `real-env` 验收 Profile。
- 密钥、日志、依赖和 Runbook 检查。

完成定义：V2 可以用固定脚本完成一次从部署到评测的完整验收。

## 11. 总体版本完成标准

每一个版本只有同时满足以下条件才算完成：

- 功能通过真实用户路径，而不只是存在类和接口。
- 核心业务逻辑有确定性测试，外部 Provider 有契约和故障测试。
- 数据、模型、Prompt、算法和 Evidence 都有版本信息。
- 失败、超时、降级、重试和取消状态可以观察和审计。
- README、启动配置、演示脚本、架构说明与代码一致。
- 没有把下一版才应解决的问题包装成当前版本已经完成。

如果以简历和学习为目标，最有性价比的阶段是完成到 V4：它会同时体现 Java 分层架构、金融领域建模、真实数据治理、Spring AI、RAG 评测和自研 Agent Runtime。V5/V6 应在 V4 稳定后继续，不应为了关键词一次性堆入当前代码。
