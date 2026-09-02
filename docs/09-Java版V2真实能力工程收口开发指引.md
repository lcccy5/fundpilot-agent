# Java 版 V2：真实能力工程收口与质量评测开发指引

> 前置版本：当前 Java V1.0。  
> 开发目标：不重复接入真实基金数据和真实模型，而是把现有真实能力重构为分层清晰、来源可追溯、故障可降级、质量可评测、前端可完整演示的 V2.0。  
> 核心约束：继续采用现有 Maven 模块化单体；V2 不做用户个人持仓、Plan-and-Execute、多 Agent、自动交易和微服务拆分。

## 1. 开始开发前必须理解的现状

### 1.1 V1 已经完成的真实能力

当前代码已经具备：

- `EastMoneyFundDataProvider`：真实基金资料、基金经理、历史单位净值和累计净值。
- `FundRealtimeQuoteTool`：基金关联 ETF 的腾讯实时行情代理。
- `SectorOutlookTool`：东方财富代表基金搜索、腾讯实时行情和 90 日 K 线情景分析。
- `FundCatalystResearchTool`：基金/ETF 解析、PCF 或披露持仓、股票行情估值、公司资料、公告核验和影响评估。
- Spring AI OpenAI 兼容 Chat/Embedding 配置、Tool Calling、MySQL 会话、Fact Card、Evidence 和 SSE。
- PDF/HTML/TXT 摄取、Elasticsearch 混合检索、RRF、页码引用、异步 Worker 和索引重建/回滚。
- Next/React `fund-web`：基金查询、净值图、临时自选和 Agent SSE 对话。

所以 V2 禁止把以下事项重新写成新功能：

- “首次接入东方财富”；
- “首次接入真实大模型”；
- “首次实现 RAG”；
- “首次实现 Agent SSE”；
- “首次实现基金实时数据”。

V2 要解决的是这些能力目前尚未形成统一工程闭环的问题。

### 1.2 当前最重要的代码问题

| 位置 | 当前问题 | V2 处理方式 |
| --- | --- | --- |
| `FundRealtimeQuoteTool` | Tool 内直接创建 `RestClient`、解析第三方字段、手写 `Thread.sleep` 重试 | 拆为 Use Case + Provider Adapter，统一 Resilience4j |
| `SectorOutlookTool` | Tool 内混合基金搜索、行情、K 线和计算 | 搜索/行情移入 Infrastructure，情景计算移入 Application 或纯 Java Service |
| `FundCatalystResearchTool` | 一个类承担多个站点、PCF 解析、持仓、行业、公告、影响和 Evidence | 拆分 Port/Adapter/Use Case，保留一条业务编排链 |
| `EvidenceReference` | 能表达来源，但市场数据类型主要塞在 `evidenceType/navBasis/dataSource` 字符串中 | 增加统一 Provenance 和强类型数据口径，再映射为 Evidence |
| 真实环境测试 | 已有 Smoke Test，但默认构建全部显式跳过 | 增加 `real-env` Profile 和可保存的验收报告 |
| RAG 评测 | 已有 Runner 和示例 JSONL | 建立真实文档语料与至少 100 条人工标注集 |
| `fund-web/app/page.tsx` | 单文件承担全部 UI，日期写死，自选只在内存，知识库占位 | 组件化并补齐指标、比较、证据和知识管理页面 |
| `fund-observability` | 模块存在但没有源码 | 放置统一指标名、Tag 和 Dashboard 约定 |

### 1.3 当前测试基线

2026-08-27 的完整 Maven 测试结果：

```text
12 个 Reactor 项目构建成功
53 项测试执行通过
7 项真实环境测试因显式开关未启用而跳过
```

V2 开发过程中不得降低这条基线。

### 1.4 双审查后的实施决策

本指引初稿完成后，分别进行了架构审查和交付审查。最终采用以下修订：

- 真实环境测试统一改为 JUnit `@Tag("real-env")` + Maven Failsafe，不再混用环境变量开关和错误的 `-D` 参数。
- V2 使用 Flyway V9；V3 从 V10 开始，避免重复版本。
- 多源结果使用 `DataLineage`，不把多个来源拼成一个字符串。
- 板块收益、波动和回撤计算进入现有 `fund-analytics`，Application 只负责编排。
- Catalyst 使用 Application 中立进度监听器保持四步 SSE/审计，不让 Application 依赖 Agent API。
- Deadline 分为 Run、Tool、Provider Attempt 三层，并约束 Retry、Backoff 和并发等待。
- 评测拆成 Routing、Tool Contract、RAG、真实端到端四类 Runner。
- Knowledge 管理先补分页列表 API，再开发管理页面。
- 浏览器不持有 Internal Token；非本地管理流量走 BFF 或独立管理入口。

## 2. V2 交付范围

### 2.1 必须交付

1. 三个真实研究 Tool 完成分层重构，Agent Runtime 不再解析供应商响应。
2. 真实数据来源、口径、新鲜度、版本和质量状态统一建模。
3. 外部 Provider 配置化、超时、重试、熔断、限流、降级和指标统一。
4. 建立真实 Chat、Embedding、Elasticsearch、MySQL 和外部 Provider 验收 Profile。
5. 分别建立 Routing、Tool Contract、安全和 RAG 评测集；真实文档 RAG 标注不少于 100 条，并生成版本对比报告。
6. Web 完成基金指标、基金比较、Evidence 详情和知识库管理闭环。
7. 补齐 Scheduler、Worker、Controller、Provider 故障和 SSE 取消回归测试。
8. 建立统一的运行指标、脱敏要求、验收脚本和 Runbook。

### 2.2 明确不做

- Spring Security 用户系统和 RBAC：放在 V3。
- 自选基金后端持久化和用户组合：放在 V3。
- ReAct、Plan-and-Execute、Checkpoint 和 HITL：放在 V4。
- 多 Agent、Outbox 主动提醒和 MCP：放在 V5。
- Kubernetes、微服务和生产集群：放在 V6。
- 自动申购赎回、券商账户连接和收益保证：整个路线默认不做。

### 2.3 V2 完成后的用户路径

```text
打开 FundPilot
  → 查询真实基金
  → 选择时间区间查看净值和指标
  → 添加 2～10 只基金进行同区间比较
  → 向 Agent 询问资料/风险/实时代理行情/板块/催化
  → 展开每条 Evidence 查看来源、日期、口径和限制
  → 上传基金文档并观察摄取任务
  → 使用知识库回答并定位到文档页码
```

## 3. 目标架构

### 3.1 统一调用方向

```text
fund-interface / fund-agent-runtime
                 ↓
            fund-application
                 ↓
              fund-domain
                 ↑
          fund-infrastructure
```

规则：

- Agent Tool 是输入/输出适配器，不承担数据采集和金融计算。
- Application Use Case 负责用例编排、Deadline、降级决策和事务边界。
- Domain 保存稳定模型、规则和 Provider Port，不依赖 Spring AI。
- Infrastructure 只负责外部协议、第三方 DTO、字段解析、缓存和持久化。
- 第三方 JSON、HTML、PCF 格式不能穿过 Infrastructure 边界。
- Tool 仍然使用 `AgentExecutionTrace` 审计，但 Evidence 必须从 Use Case 返回的 Provenance 确定性生成。

### 3.2 目标包结构

```text
fund-domain/src/main/java/com/jijing/fund/domain/research/
├─ model/
│  ├─ DataProvenance.java
│  ├─ MarketDataKind.java
│  ├─ QualityStatus.java
│  ├─ SecurityQuote.java
│  ├─ PriceBar.java
│  ├─ FundHoldingSnapshot.java
│  ├─ IndustryExposure.java
│  └─ VerifiedMarketEvent.java
└─ provider/
   ├─ FundDiscoveryProvider.java
   ├─ MarketQuoteProvider.java
   ├─ MarketHistoryProvider.java
   ├─ FundHoldingProvider.java
   ├─ CompanyProfileProvider.java
   └─ MarketEventProvider.java

fund-application/src/main/java/com/jijing/fund/application/research/
├─ RealtimeFundQuoteUseCase.java
├─ RealtimeFundQuoteApplicationService.java
├─ SectorOutlookUseCase.java
├─ SectorOutlookApplicationService.java
├─ FundCatalystResearchUseCase.java
├─ FundCatalystResearchApplicationService.java
├─ ResearchDeadline.java
├─ ResearchProgressListener.java
└─ dto/

fund-analytics/src/main/java/com/jijing/fund/analytics/research/
├─ SectorMarketCalculator.java
└─ SectorScenarioRule.java

fund-infrastructure/src/main/java/com/jijing/fund/infrastructure/research/
├─ config/ResearchProviderProperties.java
├─ eastmoney/
├─ tencent/
├─ szse/
├─ sse/
├─ persistence/
└─ metrics/

fund-agent-runtime/src/main/java/com/jijing/fund/agent/tool/
├─ FundRealtimeQuoteTool.java
├─ SectorOutlookTool.java
├─ FundCatalystResearchTool.java
└─ ToolEvidenceFactory.java
```

不要新建一个与 `fund-domain`、`fund-application` 平级但职责重复的 `fund-research` Maven 模块。V2 的目标是修复现有分层，不是增加模块数量。

当前实际 Maven 关系中，`fund-infrastructure` 因实现 Agent Port 而依赖 `fund-agent-runtime`，暂时不会形成循环。V2 还必须遵守：

- 不得新增 `fund-agent-runtime → fund-infrastructure` 依赖。
- `fund-observability` 如果提供共享指标常量，只能作为无业务反向依赖的基础模块被其他模块引用。
- Provenance 位于 Domain，不能为了复用 Evidence 让 Domain 依赖 Agent Runtime。

## 4. 第一阶段：建立强类型数据来源模型

### 4.1 `MarketDataKind`

建议在 Domain 中建立：

```java
public enum MarketDataKind {
    OFFICIAL_FUND_NAV,          // 基金公司最终确认净值
    ESTIMATED_FUND_NAV,         // 估算净值，不等于最终净值
    EXCHANGE_TRADED_QUOTE,      // ETF/股票场内实时成交行情
    UNDERLYING_ETF_PROXY,       // 用关联 ETF 代理解释场外基金盘中表现
    DAILY_PCF_BASKET,           // ETF 当日/T-1 申购赎回篮子
    DISCLOSED_FUND_HOLDING,     // 最近一期公开披露持仓
    COMPANY_ANNOUNCEMENT,       // 可定位来源的上市公司公告
    DERIVED_ANALYTICS           // 确定性算法派生结果
}
```

禁止把 `UNDERLYING_ETF_PROXY` 返回成 `OFFICIAL_FUND_NAV`。这项约束应该由类型和测试保证，不能只依赖 Prompt。

### 4.2 `DataProvenance`

```java
public record DataProvenance(
        ProviderId providerId,
        URI sourceUri,
        MarketDataKind dataKind,
        String sourceVersion,
        Instant sourceUpdatedAt,
        Instant collectedAt,
        QualityStatus qualityStatus,
        List<String> qualityWarnings) {
}
```

要求：

- `providerId` 使用枚举或受校验 Value Object，例如 `eastmoney-fund-api`、`tencent-quote`、`szse-pcf`。
- `sourceVersion` 优先使用供应商时间戳、公告 ID、披露日期或响应业务版本，不能总用当前系统时间代替。
- `sourceUri` 保存不含密钥和敏感查询参数的可审计地址。
- `qualityWarnings` 使用稳定错误码，不保存不可控的整段第三方异常。

### 4.3 Use Case 输出不依赖 Agent API

Domain/Application 不能返回 `EvidenceReference`。建议业务 DTO 返回：

```java
public record DerivationMetadata(
        String algorithmVersion,
        String ruleVersion,
        Instant calculatedAt,
        List<String> limitations) {}

public record DataLineage(
        List<DataProvenance> inputs,
        DerivationMetadata derivation) {}

public record SourcedValue<T>(T value, DataLineage lineage) {}
```

单源查询的 `inputs` 只有一项；板块和催化研究可以包含多个来源。由 `ToolEvidenceFactory` 将 `DataLineage` 中每个关键输入和派生结果转换成 Agent Evidence。这样不会退化为 `eastmoney+tencent` 字符串，也让 REST API、Scheduler 和 Agent 复用同一 Use Case。

### 4.4 Evidence 兼容策略

V2 不急着破坏性修改现有 `EvidenceReference` 构造器。先采用兼容迁移：

1. 新业务模型内部使用强类型 `DataProvenance`。
2. `ToolEvidenceFactory` 将 `MarketDataKind.name()` 映射到 `evidenceType` 或 `navBasis`，并为多源输入生成可分别定位的 Evidence。
3. 保留现有文档 Evidence 字段。
4. 增加映射单元测试，确保旧前端仍能读取。
5. 等 V3/V4 需要统一用户持仓 Evidence 时，再评估 `EvidenceReference V2`。

## 5. 第二阶段：拆分三个真实研究 Tool

### 5.1 `FundRealtimeQuoteTool` 重构

#### 当前逻辑

```text
基金代码
→ 东方财富查询关联 ETF
→ 拼接 sh/sz symbol
→ 腾讯行情查询实时价格
→ 返回 UNDERLYING_ETF_PROXY
```

#### 目标接口

```java
public interface FundDiscoveryProvider {
    Optional<LinkedExchangeFund> findLinkedExchangeFund(FundCode fundCode);
}

public interface MarketQuoteProvider {
    Optional<SecurityQuote> latestQuote(ExchangeSecurityCode securityCode);
}

public interface RealtimeFundQuoteUseCase {
    RealtimeFundQuoteResult query(String fundCode);
}
```

#### Application 规则

- 校验 6 位基金代码。
- 没有关联 ETF 时返回业务状态 `NO_EXCHANGE_PROXY`，不是系统异常。
- 行情不存在或过期时返回 `DATA_NOT_READY`。
- `quoteTime` 距当前时间超过可配置阈值时标记 `STALE`。
- 返回类型固定为 `UNDERLYING_ETF_PROXY`。
- 不再在 Tool 内手写 `Thread.sleep` 重试。

#### Tool 最终职责

```text
校验模型入参
→ trace.begin
→ trace.call(toolCall, () -> useCase.query(...))
→ ToolEvidenceFactory
→ trace.success/failure
→ FundToolEnvelope
```

### 5.2 `SectorOutlookTool` 重构

#### 当前逻辑拆分

| 当前职责 | 目标位置 |
| --- | --- |
| 清洗主题关键词 | Application |
| 搜索代表 ETF/LOF | `FundDiscoveryProvider` Adapter |
| 腾讯实时行情 | `MarketQuoteProvider` Adapter |
| 90 日 K 线 | `MarketHistoryProvider` Adapter |
| 20/60 日涨跌、波动、回撤、均线和量能 | 现有 `fund-analytics` 下纯 Java Calculator |
| 基准/乐观/悲观情景 | `fund-analytics` 规则服务，Application 负责组合 |
| Evidence | Agent `ToolEvidenceFactory` |

#### 关键规则

- 代表 ETF 的选择结果要返回候选和选择理由，不能只有最终代码。
- 当前硬编码主题兜底可以保留，但必须进入配置或规则表并带规则版本。
- `horizonDays` 当前没有真正影响算法；V2 要么实现 20～30 日范围校验并明确仅为一个月情景，要么从 Tool Schema 删除该参数，禁止保留假参数。
- 历史样本不足 61 条时返回 `INSUFFICIENT_HISTORY`。
- 情景不是收益预测，输出必须包含触发条件和失效条件。
- 百分比统一定义为“百分数”还是“小数”。当前板块 Tool 使用百分数，而核心 Analytics 使用小数；V2 API DTO 必须显式命名，如 `returnPercent20d`。

### 5.3 `FundCatalystResearchTool` 重构

#### 当前四步链保留

```text
1. get_fund_portfolio_exposure
2. map_industry_chain_exposure
3. search_verified_market_events
4. assess_event_fund_impact
```

V2 不取消这条链，只把每一步移出大 Tool 类。

Application 不能依赖 `AgentExecutionTrace`。在 `fund-application` 定义中立监听器：

```java
public interface ResearchProgressListener {
    void stageStarted(ResearchStage stage);
    void stageCompleted(ResearchStage stage, StageSummary summary);
    void stageFailed(ResearchStage stage, String safeErrorCode);

    static ResearchProgressListener noOp() { /* ... */ }
}
```

Agent Tool 传入适配器，把上述回调映射为现有四步 SSE 和审计；REST/Scheduler 使用 No-op 或自己的监听器。`AgentExecutionTrace` 增加不占用模型 Tool 次数预算的 `beginStage/completeStage`，仍把内部阶段写入审计。一次 `research_fund_catalysts` 只计为一次模型 Tool Call，避免四个内部步骤耗尽 `max-tool-calls-per-run`。

#### 目标 Use Case

```java
public interface FundCatalystResearchUseCase {
    FundCatalystResearchResult research(
            FundCatalystResearchCommand command,
            ResearchProgressListener progressListener);
}
```

监听器由调用方传入，不放进模型可构造的 Command 字段。非 Agent 调用使用 `ResearchProgressListener.noOp()`。

`FundCatalystResearchCommand` 至少包含：

```text
theme 或 fundCode（二者至少一个）
topN：1～20
lookbackDays：1～180
deadline
requestId
```

#### 持仓数据优先级

```text
当日/T-1 合法 PCF + 最新成分股行情
  > 最近公开披露持仓
  > 无可用持仓，明确拒绝计算影响权重
```

不允许在 PCF 失败后悄悄返回空持仓并继续生成事件影响。

#### 部分降级

催化研究不应任何一步失败就丢掉全部结果：

- 公司资料失败：仍可返回持仓和公告，但产业链标为部分缺失。
- 单只股票公告失败：保留其他股票结果，并降低覆盖率。
- 全部公告源失败：返回持仓暴露，但不输出“没有利好利空”。
- 行情失败：PCF 数量无法可靠转权重时，退化到披露持仓或明确不可计算。
- 影响计算失败：仍返回已核验公告，不输出权重影响结论。

结果 DTO 增加：

```text
stageStatus
holdingCoveragePercent
eventCoveragePercent
failedSources
limitations
provenanceByStage
```

#### 三层 Deadline

当前一次催化研究可能串行访问多个外部站点。V2 明确三层预算：

```text
Agent Run Deadline
└─ Tool Deadline
   └─ Provider Attempt Timeout
```

Tool 必须保留统一超时入口：

```java
trace.call(toolCall, () -> useCase.research(command, progressListener))
```

如果催化研究的预算大于默认 `fund.agent.tool-timeout=5s`，必须增加按 Tool 名称配置的 `ToolTimeoutPolicy`，不能只在 Use Case 中写 8 秒但外层 5 秒先超时。

一次参考分配：

```text
Agent Tool 总预算：例如 8s
├─ 标的解析：1s
├─ 持仓/PCF：2s
├─ 公司资料：并发且最多 2s
├─ 公告：并发且最多 2s
└─ 影响计算：本地计算，剩余预算
```

具体数值通过压测校准。每个请求必须满足：

```text
attemptTimeout <= remainingDeadline
retryBackoff + nextAttemptTimeout <= remainingDeadline
```

Deadline 不足时禁止继续 Retry。HTTP connect/read timeout 必须收缩到剩余预算；仅检查“下一步是否启动”不足以限制已经运行的慢请求。虚拟线程取消也不能替代底层 HTTP Timeout。

#### 并发边界

- 公司资料和公告允许使用 Java 21 Virtual Thread 有界并发。
- 使用 Resilience4j SemaphoreBulkhead 或一个手写 Semaphore，二选一；通过 `tryAcquire(remainingTime)` 获取，不能无限等待。
- 默认只研究 Top 10，最大 Top 20。
- 同一股票公告查询在一次 Run 内去重。
- 外部站点限流优先于追求单次报告速度。

## 6. 第三阶段：Provider Adapter 与弹性治理

### 6.1 配置结构

建议增加：

```yaml
fund:
  research:
    providers:
      eastmoney:
        enabled: true
        fund-base-url: https://fundmobapi.eastmoney.com
        search-base-url: https://fundsuggest.eastmoney.com
        holding-base-url: https://fundf10.eastmoney.com
        announcement-base-url: https://np-anotice-stock.eastmoney.com
        connect-timeout: 2s
        read-timeout: 4s
      tencent:
        enabled: true
        quote-base-url: https://qt.gtimg.cn
        kline-base-url: https://web.ifzq.gtimg.cn
        connect-timeout: 2s
        read-timeout: 3s
      exchange:
        pcf-enabled: true
        connect-timeout: 2s
        read-timeout: 4s
    freshness:
      realtime-quote-max-age: 10m
      daily-pcf-max-trading-days: 1
      disclosed-holding-max-age: 120d
    catalyst:
      total-timeout: 8s
      max-concurrency: 6
      default-top-n: 10
      default-lookback-days: 45
```

地址必须可配置，但配置只接受 `https` 和预期 Host；避免把配置能力变成任意 SSRF 出口。

新鲜度不能只按墙钟 Duration 判断。复用现有 `TradingCalendarRepository`：开市时实时行情按分钟判断；收市后允许最近交易日收盘快照并标记 `MARKET_CLOSED_LAST_SESSION`；周末/节假日的 PCF 按最近有效交易日判断，不能因为超过 36 小时把合法数据判为失效。

### 6.2 Resilience4j 实例

不同数据源使用不同实例，禁止所有站点共用一个熔断器：

```text
eastMoneyFund
eastMoneyHolding
eastMoneyAnnouncement
tencentQuote
tencentKline
szsePcf
ssePcf
```

同步 `RestClient` 的硬超时依靠 HTTP connect/read timeout，不假定 Resilience4j `TimeLimiter` 注解能自动中断同步 I/O。一次逻辑调用的职责顺序明确为：

```text
Provider Bulkhead 获取名额
→ RateLimiter 获取配额
→ 在总 Deadline 内执行一次或多次 Attempt
→ 每次 Attempt 使用收缩后的 HTTP timeout
→ Retry 只处理允许错误并服从剩余 Deadline
→ CircuitBreaker 记录一次逻辑调用的最终结果
```

只对 GET、无副作用且符合重试错误码的请求重试。字段解析错误默认不重试，因为通常是接口契约发生变化。

不要同时使用手写 Semaphore 和 Resilience4j SemaphoreBulkhead。`@Retry` 等注解必须作用在 Spring 管理的 Adapter Bean 上并通过代理调用；如果 Deadline-aware Retry 无法用注解清晰表达，使用显式 Decorator，但必须有测试证明 429、超时和解析错误的不同处理。

### 6.3 第三方 DTO

每个 Adapter 单独定义 DTO：

```text
EastMoneyFundBasicResponse
EastMoneyFundPositionResponse
TencentQuoteFields
TencentKlineResponse
SzsePcfDocument
SsePcfResponse
EastMoneyAnnouncementResponse
```

要求：

- DTO 不进入 Domain/Application。
- 解析器有保存的脱敏 Fixture 测试。
- 缺少关键字段时抛稳定 `ProviderContractException`。
- 非关键字段缺失产生 warning，不直接丢弃整条记录。
- HTML/CSV/PCF 解析分别测试字符集、空行、字段顺序和格式漂移。

### 6.4 Provider 观测表

V2 新增 Flyway `V9__create_provider_governance_tables.sql`：

```sql
CREATE TABLE data_provider_status (
    provider_name VARCHAR(64) NOT NULL,
    operation_name VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_success_at DATETIME(3) NULL,
    last_failure_at DATETIME(3) NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_safe_error_code VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (provider_name, operation_name)
);

CREATE TABLE data_quality_issue (
    issue_id CHAR(36) NOT NULL,
    provider_name VARCHAR(64) NOT NULL,
    operation_name VARCHAR(64) NOT NULL,
    subject_key VARCHAR(128) NULL,
    issue_type VARCHAR(64) NOT NULL,
    issue_fingerprint CHAR(64) NOT NULL,
    occurrence_count BIGINT NOT NULL DEFAULT 1,
    severity VARCHAR(16) NOT NULL,
    source_version VARCHAR(128) NULL,
    safe_detail VARCHAR(500) NULL,
    status VARCHAR(32) NOT NULL,
    first_seen_at DATETIME(3) NOT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    resolved_at DATETIME(3) NULL,
    PRIMARY KEY (issue_id),
    UNIQUE KEY uk_quality_fingerprint (issue_fingerprint),
    KEY idx_quality_open (status, severity, last_seen_at),
    KEY idx_quality_provider (provider_name, operation_name, last_seen_at)
);

CREATE TABLE provider_observation (
    observation_id CHAR(36) NOT NULL,
    provider_name VARCHAR(64) NOT NULL,
    operation_name VARCHAR(64) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    field_name VARCHAR(64) NOT NULL,
    normalized_value_json JSON NULL,
    value_hash CHAR(64) NOT NULL,
    source_version VARCHAR(128) NULL,
    source_updated_at DATETIME(3) NULL,
    collected_at DATETIME(3) NOT NULL,
    PRIMARY KEY (observation_id),
    KEY idx_observation_compare (subject_key, field_name, collected_at)
);

CREATE TABLE provider_reconciliation_record (
    reconciliation_id CHAR(36) NOT NULL,
    left_observation_id CHAR(36) NOT NULL,
    right_observation_id CHAR(36) NOT NULL,
    result VARCHAR(32) NOT NULL,
    selected_observation_id CHAR(36) NULL,
    rule_version VARCHAR(64) NOT NULL,
    safe_reason VARCHAR(500) NULL,
    reconciled_at DATETIME(3) NOT NULL,
    PRIMARY KEY (reconciliation_id),
    KEY idx_reconciliation_time (reconciled_at)
);
```

V2 暂不保存每一次大体量原始响应。Observation 只保存规范化关键值或 Hash，必要的脱敏响应 Fixture 存测试资源。`data_provider_status` 由状态转换或节流任务更新，不在每次请求同步更新热点行；质量问题按 fingerprint 幂等 upsert，并定义恢复关闭和保留期。Domain/Application 通过 `ProviderObservationRepository`、`DataQualityIssueRepository` Port 写入，不能直接引用 Infrastructure Mapper。

### 6.5 第二数据源原则

V2 可以接第二数据源，但不是硬性要求“所有字段双源”：

- 优先为基金代码/名称、官方净值日期和值做交叉验证。
- 只有获得稳定且允许使用的数据源后才接入。
- 双源冲突时保留两侧 Observation，不用后到数据静默覆盖先到数据。
- 没有第二来源时，依靠时间序列连续性、字段契约和来源新鲜度做质量校验。
- 禁止为了简历关键词增加无法维护的全网爬虫。

## 7. 第四阶段：真实模型、RAG 和 Agent 评测

### 7.1 三层门禁

```text
PR Gate
  MockWebServer + Fixture + Fake Model，全确定性，必须阻断

Nightly Gate
  专用 MySQL + Elasticsearch + 固定 Chat/Embedding + 真实 Provider Smoke
  允许基础设施不可用重试一次，但必须保存结果

Release Gate
  真实语料评测 + Agent 端到端 + 成本 + 故障注入 + 人工抽检
  不允许 SKIPPED
```

报告必须区分 `FAILED`、`INFRA_UNAVAILABLE`、`SKIPPED`。真实外部服务不稳定，不能把全部真实测试放进每个 PR 的不可解释红灯里。

### 7.2 Maven `real-env` Profile

现有四类真实测试使用 `@EnabledIfEnvironmentVariable`，且开关名称不统一。V2 统一改造：

1. 将真实测试重命名为 `*IT`。
2. 使用 JUnit `@Tag("real-env")`，移除四个环境变量启用注解。
3. 默认 Surefire 不执行 `*IT`。
4. 根 `real-env` Profile 只提供公共属性；Failsafe execution 仅配置在实际拥有 IT 的 `fund-bootstrap`、`fund-infrastructure` 模块。
5. 这两个模块设置 `failIfNoTests=true`；没有 IT 的其他 Reactor 模块不能继承该失败策略。
6. 验收脚本读取 XML 并断言关键 Suite 存在、executed > 0、`skipped=0`。

```text
MySqlMigrationIT
RealElasticsearchIT
RealEmbeddingIT
RealModelAgentIT
RealProviderSmokeIT
```

Profile 不保存密钥。MySQL 必须使用专用 `jijing_agent_test`，继续保留现有测试对数据库名的强校验。以下是 V2 验收所需变量；脚本只检查“是否存在”和可达性，禁止打印值：

| 能力 | 必需变量 | 可选变量/约束 |
|---|---|---|
| MySQL | `MYSQL_TEST_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD` | URL 中数据库名必须精确为 `jijing_agent_test` |
| Elasticsearch | `ELASTICSEARCH_URI` | 按部署方式选用 `ELASTICSEARCH_USERNAME`、`ELASTICSEARCH_PASSWORD`、`ELASTICSEARCH_API_KEY`、`ELASTICSEARCH_CA_CERTIFICATE`；索引由 IT 使用随机后缀隔离 |
| Chat Model | `AI_CHAT_PROVIDER`、`AI_CHAT_BASE_URL`、`AI_CHAT_API_KEY`、`AI_CHAT_MODEL` | 固定模型和参数，报告记录模型名但不记录 Key |
| Embedding Model | `AI_EMBEDDING_PROVIDER`、`AI_EMBEDDING_BASE_URL`、`AI_EMBEDDING_API_KEY`、`AI_EMBEDDING_MODEL`、`AI_EMBEDDING_DIMENSIONS` | 维度必须与 ES Mapping 一致 |
| 基金数据源 | `FUND_PROVIDER_TYPE=eastmoney`、`FUND_DATA_BASE_URL`、`FUND_DATA_HISTORY_BASE_URL` | 若后续使用授权源，再通过 `FUND_DATA_API_KEY` 注入凭据 |
| 真实语料 | `RAG_EVALUATION_DATASET_DIR` | 目录内必须有 Manifest、SHA-256 和授权/来源说明，不允许临时下载未冻结语料 |

示例只展示非敏感占位符：

```powershell
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS jijing_agent_test CHARACTER SET utf8mb4"
$env:MYSQL_TEST_URL='jdbc:mysql://127.0.0.1:3306/jijing_agent_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:MYSQL_USERNAME='root'
$env:MYSQL_PASSWORD='测试库密码'
# 其余变量配置完成后，由脚本先检查/启动依赖，再运行 Maven 与评测
.\scripts\verify-v2-real-env.ps1 -StartLocalDependencies
```

Chat、Embedding、ES 配置继续从受保护环境变量取得。构建成功但任一关键 IT 未执行，验收仍然失败。

注意：`real-env` Profile、`*IT` 命名、`RAG_EVALUATION_DATASET_DIR` 和下文验收脚本都是 **V2 要实现的目标态**。当前 V1 仍使用四个 `RUN_*` 环境变量控制 Smoke Test，不能把本节命令误认为已经存在的能力。

### 7.3 四套评测数据与 Runner

不能把路由、Tool 契约和 RAG 混成一个 100 条集合。按能力拆分：

```text
fund-agent-runtime/src/test/resources/evals/
├─ routing-v2.jsonl              # 80～100 条：期望/禁止 Tool、顺序规则
├─ tool-contract-v2.jsonl        # 固定 Fixture：参数、状态、DataKind、warning、Evidence predicate
├─ evidence-policy-v2.jsonl      # Claim/Evidence 类型和日期范围
└─ safety-v2.jsonl               # 收益保证、交易诱导、提示注入

fund-test-support/src/main/resources/evals/rag-real/
├─ documents-manifest.jsonl
├─ retrieval-cases-v2.jsonl      # 至少 100 条真实文档检索问题
└─ answer-cases-v2.jsonl         # 真实端到端问答、期望事实和拒答
```

市场 Evidence ID 含 UUID，标注集不能写死它。使用 Predicate：

```text
evidenceType + fundCode + providerId + dataKind + 日期范围
```

共享 JSONL 放入 `fund-test-support` 主资源，是为了让 `fund-bootstrap` 的 test-scope 依赖能够读取；Maven 普通依赖不会传递另一个模块的 `src/test/resources`。真实文档若不适合进入 Git，放受控目录，通过 Manifest、授权说明和 SHA-256 固定版本。标准答案必须人工确认，不能用当前模型答案自动生成。若未来不希望 JSONL 进入普通 Jar，再改为专用 eval artifact/test-jar。

### 7.4 可执行 Harness

在 `fund-bootstrap` 增加对 `fund-test-support` 的 test-scope 依赖，由 Bootstrap 测试环境装配完整系统：

```text
AgentRoutingEvaluationTest       # PR
ToolContractEvaluationTest       # PR
RagRetrievalEvaluationIT         # Nightly/Release
AgentEndToEndEvaluationIT        # Release
EvaluationBaselineComparator     # 读取 baseline，决定构建是否失败
target/evaluation/*.json         # 报告输出
```

现有 `RagEvaluationRunner` 需要修订：

- 门槛从硬编码改为配置化绝对下限、相对退化上限和分类最低值。
- 负例增加 `expectedNoRelevantHit/maxIrrelevantHits` 或 false-positive rate，不能在无 relevant chunk 时无条件得满分。
- 安全评测检查策略结果和危险语义，不只搜索几个禁词。
- 每次报告记录最小样本数，样本不足不能通过。

### 7.5 指标

Routing/Tool Contract：Tool 集合、禁止 Tool、参数合法率、业务状态、重复调用、`MarketDataKind`、warning 和 Evidence Predicate。

RAG：Recall@5/10、MRR@10、nDCG@10、负例误召回、BM25/Vector/RRF/Rerank 分阶段结果、引用准确率和拒答。

端到端：任务完成率、Claim Evidence 覆盖率、P50/P95、Tool 数、Token、费用和稳定率。真实模型固定 Model、Prompt、temperature，同一关键案例至少重复 3 次并报告 pass@1/稳定率。

### 7.6 基线与门禁

```text
生成 baseline-v2.0.0.json
→ 修改模型/Prompt/Tool/RAG
→ 生成 candidate.json
→ 检查绝对下限、分类下限和相对退化
→ 任一关键项失败则阻断对应 Gate
```

先用第一批人工数据校准阈值，再冻结基线。禁止为通过门禁降低题目难度。

## 8. 第五阶段：Web 研究工作台闭环

### 8.1 拆分当前单文件页面

将 `fund-web/app/page.tsx` 拆为：

```text
fund-web/
├─ app/
│  ├─ page.tsx
│  ├─ funds/[code]/page.tsx
│  ├─ compare/page.tsx
│  └─ knowledge/page.tsx
├─ components/
│  ├─ FundSearch.tsx
│  ├─ FundProfileCard.tsx
│  ├─ NavChart.tsx
│  ├─ MetricsPanel.tsx
│  ├─ FundComparison.tsx
│  ├─ AgentChat.tsx
│  ├─ AgentTrace.tsx
│  └─ EvidenceDrawer.tsx
└─ lib/
   ├─ api.ts
   ├─ sse.ts
   └─ types.ts
```

不要在 V2 引入大型状态管理框架。现有页面规模使用 React State + 小型 API Client 即可。

### 8.2 动态日期

当前日期区间写死到 2026-08-24。改为：

- 1M、3M、6M、1Y、3Y、成立以来和自定义。
- 以浏览器当前日期生成请求，但最终实际起止日期以后端返回为准。
- 交易日缺口由后端 Coverage 表达，前端不自行补零。
- 用户切换区间时同时刷新净值和指标。

### 8.3 指标页

直接调用现有：

```text
GET /api/v1/funds/{fundCode}/metrics?startDate=...&endDate=...&navBasis=...
```

必须展示：

- actualStartDate/actualEndDate；
- navBasis；
- observationCount/coverage；
- cumulativeReturn/annualizedReturn/volatility/maxDrawdown/sharpe；
- `UNAVAILABLE` 原因；
- dataVersion/algorithmVersion/calculatedAt。

### 8.4 比较页

调用现有：

```text
POST /api/v1/fund-comparisons

body = { fundCodes, startDate, endDate, navBasis }
```

要求：

- 2～10 只基金；
- 显示公共实际区间和净值口径；
- 指标不可用的基金不伪造排名；
- 排名只是历史数据排序，页面显示非推荐声明。

### 8.5 Evidence Drawer

当前只把 Evidence ID 渲染为“证据”徽标。V2 点击后显示：

```text
evidenceType
fundCode
dataSource/sourceUri
actualStartDate/actualEndDate
dataVersion/algorithmVersion
collectedAt/publishedDate
documentTitle/pageStart/pageEnd/excerpt
数据口径和 limitations
```

前端不能从回答文本猜 Evidence。`answer.completed.data` 是完整 `FundAgentResponse`，使用 `event.data.evidence` 建立映射。SSE 同时存在 HTTP `event:` 和 JSON 内 `FundAgentEvent.type`，解析器以 JSON `type` 为业务依据，并在开发模式校验两者一致。

### 8.6 Knowledge 管理页

现有 API 只能通过已知 ID 查询，无法在刷新后浏览已有数据。先新增并冻结以下分页契约：

```text
GET /internal/v1/knowledge/documents?cursor=&limit=&fundCode=&type=
GET /internal/v1/knowledge/documents/{documentId}/versions?cursor=&limit=
GET /internal/v1/knowledge/ingestion-jobs?status=&cursor=&limit=
GET /internal/v1/knowledge/index-rebuilds?cursor=&limit=
```

集合接口必须限制最大页大小、使用稳定排序并返回 nextCursor。前端统一按现有 `ApiResponse<T>.data` 解包。然后复用其余 `/internal/v1/knowledge/**` 完成：

- 上传 PDF/HTML/TXT；
- 查看 document/version/job；
- 轮询摄取状态；
- 失败任务重试；
- Search Debug；
- 创建索引重建；
- 激活和回滚。

V2 尚无完整用户 RBAC：

- 本地模式：后端与前端只绑定 loopback，Internal API 默认关闭，通过 `local-admin` 开关显式启用。
- 非本地模式：使用 Vinext Server Route/BFF 在服务端持有内部令牌，或使用独立管理端口 + 反向代理认证。
- 浏览器不得获得内部令牌；禁止把令牌放入 Bundle、`NEXT_PUBLIC_*`、query string 或 localStorage。
- Internal Filter fail-closed、常量时间比较，并测试缺失/错误令牌为 401/403。
- `/internal/**` 不复用普通前端的宽松 CORS。

V3 再迁移到 Spring Security RBAC。在上述保护完成前，Knowledge 管理页不能作为非本地部署能力验收。

### 8.7 SSE 取消

- 前端使用 `AbortController` 取消旧请求和页面卸载请求。
- V1 的 `SpringAiFundAgentService` 已经在 Reactor CANCEL 时记录 `CANCELLED/AGENT_STREAM_CANCELLED`；V2 做 Controller → Service → Repository 回归验证，不把它描述为首次实现。
- 验证完成、错误、取消竞争时只终结 Run 一次，并尽可能取消上游模型订阅和 Tool。
- 日志中常见的 Broken pipe/连接中止降为预期取消事件，仍记录 Run 状态和取消时间。
- 增加 MockMvc/WebTestClient 或集成测试验证取消审计。
- V2 不做 SSE replay。当前 Event ID 只是 `runId`；断线续传需要 `runId:sequence` 和事件存储，留到 V4 长任务实现。

## 9. 第六阶段：可观测、安全和运行治理

### 9.1 统一指标

在 `fund-observability` 定义指标名称常量和低基数 Tag 约束：

```text
fund.provider.request.total{provider,operation,result}
fund.provider.request.duration{provider,operation}
fund.provider.data.age{provider,data_kind}
fund.data.quality.issue.total{provider,issue_type,severity}
fund.agent.run.total{mode,status,model}
fund.agent.tool.total{tool,status}
fund.agent.token.total{model,type}
fund.rag.retrieval.duration{channel}
fund.rag.zero_hit.total
fund.evidence.validation.total{result,claim_type}
fund.knowledge.job.total{status,step}
```

禁止把 `fundCode`、`userId`、`runId`、异常消息作为 Metrics Tag，避免高基数。它们只能进入日志/Trace。

### 9.2 结构化日志

每条外部调用日志至少带：

```text
requestId / runId / toolName / provider / operation /
durationMs / result / safeErrorCode / sourceVersion
```

不能记录：

- API Key、Authorization、Cookie；
- 完整第三方响应；
- 用户完整提问和模型完整上下文；
- 带敏感参数的 Source URI。

### 9.3 模型费用

当前 `agent_run` 已保存 Token。V2 增加配置化价格表用于估算，不把价格硬编码到业务代码：

```yaml
fund:
  agent:
    model-pricing:
      some-model:
        prompt-per-million: 0
        completion-per-million: 0
```

价格未知时记录 Token，不伪造费用为 0。

### 9.4 密钥

- 本机私有配置必须在 `.gitignore` 中。
- 示例文件只保留占位符。
- CI 使用 Secret 注入。
- 日志、异常和验收报告做密钥模式扫描。
- 已经暴露或复制到不可信位置的 Key 必须轮换，不能只删除文件。

### 9.5 文档与 Prompt Injection 安全

- 上传不能只相信客户端 `Content-Type`，校验扩展名、magic bytes、实际内容类型和最大字节数。
- 限制 PDF 页数、解析时间、展开后文本量和单文档 Chunk 数，防止压缩/解析炸弹。
- 文件名、标题和 excerpt 输出到 Web 时转义；Evidence URL 只允许 `http/https`，新窗口使用 `noopener noreferrer`。
- 上传文档和第三方网页一律视为不可信数据，不能改变系统 Prompt、Tool 权限或安全策略。
- 安全评测加入“文档要求忽略系统规则、泄露密钥、调用危险工具、伪造 Evidence”等案例。
- 现有 `AllowlistedHttpDocumentProvider` 已做私网地址和重定向保护；继续补 DNS rebinding、IPv6、混合解析和重定向链测试。
- 生产/非本地环境 Host allowlist 必填，只有测试 Profile 允许 MockWebServer 的 HTTP 地址。

### 9.6 数据留存与来源使用边界

- 定义 conversation、message、agent_run、tool arguments、评测报告和质量 Observation 的保留期与清理任务。
- Tool 参数做字段级脱敏，提供删除会话/评测数据的运维命令。
- 即使 V2 没有用户系统，也假设用户可能输入个人持仓或敏感信息；备份和导出需要扫描。
- 每个 Provider 记录使用条款、限频、缓存、再分发限制和维护责任。
- 东方财富、腾讯等未取得正式授权的来源继续标记为学习/演示环境，完成弹性治理不等于获得生产授权。
- Release 文档持续声明“ETF 代理行情不等于场外基金官方净值，内容不构成投资建议”。

## 10. 测试开发指引

### 10.1 Domain/Application 单元测试

必须覆盖：

- ETF 代理行情永远不会被标记成官方净值。
- 开市、收市、周末和节假日的新鲜度状态。
- 板块历史样本不足。
- `horizonDays` 边界或删除后的 Schema。
- 百分比口径。
- PCF → 披露持仓降级。
- 公告部分失败与覆盖率。
- 影响计算的权重边界。
- 总 Deadline 到期后不启动新任务。
- Provider 慢响应 + Retry + Backoff 仍不明显突破实际墙钟 Deadline。
- 第二来源冲突不静默覆盖。

### 10.2 Adapter 契约测试

每个外部 Adapter 使用 MockWebServer 或本地 Fixture：

- 正常响应；
- 404/429/500；
- 超时；
- 空 body；
- 字段缺失；
- 字段顺序变化；
- 非预期字符集；
- HTML/JSON 错误页伪装成 200；
- 限流与熔断打开；
- 重试只发生在允许的错误上。

### 10.3 Tool 测试

Tool 测试 Mock Use Case，不 Mock HTTP：

- 输入 Schema；
- Use Case 业务状态 → `ToolResultStatus` 映射；
- Provenance → Evidence 映射；
- `trace.success/failure`；
- 外层模型 Tool Call 只计一次，四个内部 Stage 不占模型 Tool 次数预算；
- `ResearchProgressListener` 正确映射四步 SSE 和审计；
- Fact Card TTL；
- 返回给模型的 limitation。

### 10.4 Web 测试

当前前端没有测试脚本。V2 引入 Vitest + React Testing Library，在 `package.json` 增加 `test` 和 `test:ci`：

- API Client 类型解析。
- 时间区间计算。
- 指标 `UNAVAILABLE` 展示。
- 多基金公共区间。
- SSE 分块、半包、多事件、错误和取消。
- Evidence ID 与结构化 Evidence 匹配。
- Knowledge Job 状态轮询停止条件。
- Internal Token 不进入浏览器 Bundle 和请求 query string。

SSE Parser 必须独立成纯模块，覆盖 CRLF、多字节字符、半包、多个事件、HTTP event/JSON type 不一致、错误和取消。再增加至少一个 Playwright 或等价端到端流程：

```text
查询基金 → 指标 → 基金比较 → Agent SSE → Evidence Drawer → 文档上传/任务完成
```

### 10.5 架构测试

建议加入 ArchUnit：

```text
domain 不依赖 Spring/Infrastructure/Agent
application 不依赖 Infrastructure/Agent/Interface
agent tool 不直接引用 RestClient/HttpClient/第三方 DTO
infrastructure 第三方 DTO 不被 Application 引用
controller 不直接引用 Mapper/Repository 实现
```

这项测试能防止 V2 重构后再次把快捷 HTTP 逻辑塞回 Tool。

## 11. 开发迭代与提交顺序

### Iteration 0：冻结基线（0.5～1 天）

- 保存当前 Maven 测试报告。
- 为三个 Tool 补关键现状测试，确保迁移不改变用户语义。
- 记录现有 SSE 示例和 API 响应 Fixture。
- 建立 V2 Feature Flag，允许旧/新实现短期切换。
- 先把真实测试改为 `@Tag + Failsafe`，确保后续验收不会静默跳过。

建议提交：

```text
test: freeze v1 research tool behavior before v2 refactor
```

### Iteration 1：Domain Port 与 Provenance（2～3 天）

- 建立强类型研究模型和 Port。
- 建立 `ToolEvidenceFactory`。
- 完成映射与架构测试。

```text
feat: add typed research data provenance and provider ports
```

### Iteration 2：实时与板块工具迁移（3～4 天）

- 迁移基金发现、腾讯行情和 K 线 Adapter。
- 实现 Realtime/Sector Use Case。
- Tool 改为薄适配层。

```text
refactor: move realtime and sector research behind application use cases
```

### Iteration 3：催化研究迁移（5～7 天）

- 拆 PCF、披露持仓、公司资料和公告 Adapter。
- 实现 Deadline、有界并发、部分降级和覆盖率。
- 通过 `ResearchProgressListener` 保持四步 Trace 名称兼容前端，并区分模型 Tool 与内部 Stage。

```text
refactor: make catalyst research recoverable and source-aware
```

### Iteration 4：治理与观测（3～4 天）

- V9 表。
- Provider 状态和质量问题。
- Resilience4j 实例、指标、结构化日志和 Dashboard。

```text
feat: add provider health and data quality governance
```

### Iteration 5A：评测 Harness（2～4 天）

- `real-env` Profile。
- Routing、Tool Contract、RAG、End-to-End Runner。
- 负例指标、配置化阈值、基线比较器和报告目录。

### Iteration 5B：真实语料与标注（4～7 天，可与重构并行）

- 真实文档 Manifest、Hash 和使用边界。
- 至少 100 条 RAG 检索标注，并扩充路由/Tool/安全数据集。
- 人工复核、基线/候选报告和门禁。

```text
test: add real environment and agent rag quality gates
```

### Iteration 6A：Knowledge API 与管理安全（2～4 天）

- 文档、版本、任务和索引重建分页列表 API。
- Local Admin 开关、Internal Filter、独立 CORS/BFF 方案。

### Iteration 6B：Web 闭环（4～6 天）

- 页面组件化。
- 动态区间、指标、比较、Evidence Drawer。
- Knowledge 管理页、SSE 取消回归、Vitest 和端到端测试。

```text
feat: complete fund research and knowledge workflows in web
```

### Iteration 7：发布验收（2～3 天）

- 默认/真实测试。
- 故障注入。
- 安全检查。
- 实现 `scripts/verify-v2-real-env.ps1`：环境预检、可选启动本地依赖、执行真实 IT/评测、核验 XML、生成汇总报告。
- Runbook、演示脚本和 README。

```text
docs: add v2 operations runbook and acceptance evidence
```

一名开发者现实预计 6～8 周，其中真实语料准备和人工标注是独立工作包。若必须压缩为 4～6 周，把高级 Dashboard、第二数据源和非本地 Knowledge 管理面列为 V2.1，但 Tool 分层、数据口径和评测 Harness 不能裁掉。

## 12. V2 验收清单

### 12.1 架构

- [ ] 三个研究 Tool 不导入 `RestClient`、`HttpClient` 或第三方解析类。
- [ ] Application 不依赖 Agent API。
- [ ] 第三方 DTO 只存在于 Infrastructure。
- [ ] ArchUnit 规则通过。

### 12.2 数据

- [ ] 东方财富真实基金资料和净值行为未退化。
- [ ] ETF 实时代理明确标记 `UNDERLYING_ETF_PROXY`。
- [ ] PCF、披露持仓、公告都有来源、时间、版本和质量状态。
- [ ] 部分来源失败时返回覆盖率和 limitation。
- [ ] 字段漂移会产生质量问题和指标。
- [ ] 新鲜度按交易日历判断，周末/节假日不会误报。
- [ ] 双源冲突保留两侧 Observation 和裁决记录。

### 12.3 Agent/RAG

- [ ] 50 条原路由集继续通过，Routing V2 扩到 80～100 条。
- [ ] Tool Contract 与安全评测集独立执行。
- [ ] 至少 100 条真实文档 RAG 检索集完成版本冻结。
- [ ] Chat/Embedding/ES/MySQL/Provider 真实验收可显式执行且关键 Suite `skipped=0`。
- [ ] 报告包含质量、延迟、Token 和费用。
- [ ] 所有关键 Claim 有匹配 Evidence 或明确拒答。

### 12.4 Web

- [ ] 日期不再写死。
- [ ] 指标页展示口径、覆盖率和不可用原因。
- [ ] 比较页使用公共实际区间。
- [ ] Evidence Drawer 展示结构化来源详情。
- [ ] Knowledge 页面可完成上传到检索的全流程。
- [ ] Knowledge 列表分页、Internal Filter 和 BFF/本地管理边界通过测试。
- [ ] V1 的 SSE `CANCELLED` 语义通过端到端回归，不产生误导性 500。

### 12.5 工程

- [ ] 默认 `mvn verify` 全绿且不需要外部密钥。
- [ ] `real-env` 在受控环境运行成功。
- [ ] 前端 lint、测试和 build 通过。
- [ ] 日志和构建产物没有密钥。
- [ ] Provider 故障、Redis/ES/模型故障 Runbook 可执行。

## 13. 推荐验收命令

本节命令是 V2 交付后的目标态验收流程，不代表当前 V1 已经具备对应 Profile、前端测试脚本和一键脚本。

默认后端：

```powershell
mvn verify
```

真实环境推荐一键入口：

```powershell
# 首次或本地验收：可由脚本启动 Redis、Elasticsearch，并等待健康
.\scripts\verify-v2-real-env.ps1 -StartLocalDependencies

# 已有受控依赖环境：只做预检和验收
.\scripts\verify-v2-real-env.ps1
```

脚本必须按以下顺序执行，任一步失败立即以非零退出码结束：

1. 检查 7.2 的必需环境变量，但不回显其值。
2. 校验 `MYSQL_TEST_URL` 的数据库名精确为 `jijing_agent_test`。
3. 使用 `-StartLocalDependencies` 时先执行 `docker compose --profile knowledge up -d redis elasticsearch`，随后轮询 Redis/ES 健康状态；不允许固定 `sleep` 后盲跑。
4. 检查 MySQL、ES、Chat、Embedding 和基金 Provider 的连通性。
5. 执行 `mvn -Preal-env verify`。
6. 解析两个模块的 Failsafe XML，断言五个关键 Suite 均存在、executed > 0 且 `skipped=0`。
7. 执行真实 RAG 与 Agent 端到端评测，比较冻结基线和阈值。
8. 生成 `target/v2-acceptance/summary.json` 和脱敏 Markdown 报告，写入版本、数据集 Hash、模型名、指标、耗时和失败分类，绝不写入密钥。

前端：

```powershell
cd fund-web
npm run lint
npm run test:ci
npm run build
```

若需要人工排障，可先单独启动依赖：

```powershell
docker compose --profile knowledge up -d redis elasticsearch
```

Compose 不提供 MySQL。真实验收前需使用本机/专用 MySQL 创建 `jijing_agent_test`，严禁把迁移集成测试指向开发库或生产库。顺序必须是“依赖健康 → 环境预检 → Maven/评测”，不能先运行真实测试再启动 ES。

启动后依次验证：

```text
Actuator health
真实基金同步和查询
基金指标
多基金比较
Agent REST/SSE
实时 ETF 代理行情
板块情景
催化研究的四步 Evidence
文档上传/摄取/检索
ES 索引重建/激活/回滚
Provider 故障降级
```

## 14. V2 完成定义

V2 不是“代码搬完”就结束。只有以下陈述全部成立才算完成：

> 项目已经接入的真实基金、行情、持仓、公告和模型能力均通过清晰的分层对外提供；数据类型、来源、时间和质量可追溯；单个第三方站点异常不会产生伪造结论；真实 Agent/RAG 质量能够自动评测；用户可以在 Web 中完成核心研究和证据核验路径；默认构建与真实环境验收都有可保存的证据；未授权公开来源仍明确限定为学习演示用途。

达到该状态后再进入 V3 用户与个人组合建设，避免把用户数据建立在尚未收口的外部数据链路上。
