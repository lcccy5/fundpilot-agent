# Java 版 V3：多用户个人组合与个性化 Agent 开发指引

> 适用项目：D:\jijing-agent  
> 目标版本：V3.0  
> 前置版本：V2“真实能力工程收口与质量评测”达到完成定义  
> 文档性质：基于当前 Java 仓库的实施设计，不是脱离代码的概念大纲

## 0. 先说结论

V3 的主题不是继续增加公开基金数据源，也不是提前实现多 Agent。V3 要把当前“所有人共享的一套研究能力”升级为“每个用户拥有独立数据和个性化研究上下文”的基金助手：

~~~text
公开基金事实 + 用户自己的交易流水
                    ↓
          可重建持仓与收益风险
                    ↓
    带用户归属、口径、日期和证据的 Agent
~~~

完成 V3 后，项目应当能回答以下问题：

- 我关注了哪些基金？
- 我在什么日期、以什么成本买入或赎回？
- 我的当前份额、持仓成本、已实现/未实现收益是多少？
- 我的组合近一年真实资金收益、最大回撤和集中风险如何？
- Agent 为什么得出这条个性化结论，引用的是哪笔流水、哪天净值和哪个算法版本？

V3 仍然只做研究辅助，不连接券商、不自动下单、不生成确定性买卖指令。

## 1. 当前仓库基线与进入 V3 的前置门禁

### 1.1 已核对的当前代码

当前项目仍是 12 个 Maven Reactor 项目，核心依赖方向为：

~~~text
fund-interface ─┐
fund-scheduler ─┼─> fund-application / fund-knowledge / fund-agent-runtime
fund-agent-runtime ─> fund-application / fund-domain
fund-infrastructure ─> 各层定义的 Port
fund-bootstrap ─> 负责最终装配
~~~

当前和 V3 直接相关的事实如下：

| 当前能力 | 代码现状 | V3 影响 |
|---|---|---|
| 数据库 | Flyway 当前代码到 V8 | V2 若按指引完成使用 V9，V3 从 V10 开始 |
| 用户身份 | 没有 Spring Security、用户表和 Principal | V3 首先建立身份边界 |
| Agent 会话 | agent_conversation 没有 user_id | 必须迁移归属，防止只凭 conversationId 越权 |
| Agent 请求 | FundAgentRequest 只有 conversationId、message、requestId | userId 必须由认证上下文注入，不能由请求体或模型传入 |
| 自选 | fund-web/app/page.tsx 中是 React 内存数组 | 改为后端持久化，支持跨设备 |
| 组合 | 催化工具内部 Portfolio 表示“基金披露持仓” | V3 的 UserPortfolio 是用户资产，必须避免同名混淆 |
| 收益指标 | fund-analytics 已有产品收益、波动率、回撤 | V3 在同一纯 Java 模块增加用户组合算法 |
| Agent 证据 | 已有 Evidence、Fact Card、Run/Tool 审计 | 增加 userId、组合快照和算法版本边界 |
| Web | Vinext + React 19，主页面仍集中在 page.tsx | V3 拆分认证、自选、组合、导入和 Agent 页面 |

本次盘点执行默认离线 Maven 测试：64 个测试实际执行并通过，7 个真实环境测试显式跳过。它证明当前默认基线可用，但不等同于 V2 的 real-env 验收已完成。

### 1.2 当前 V2 代码同步结论

仓库已出现 V2 的部分实现：

- fund-domain 已有 DataProvenance、DataLineage、ProviderId、MarketDataKind。
- 实时 ETF 代理行情已拆出 RealtimeFundQuoteUseCase。
- 东方财富基金发现和腾讯行情已有 Provider Adapter。
- ToolEvidenceFactory 已能从 DataLineage 生成 Evidence。

但当前代码中尚未看到 V2 指引要求的全部完成物：

- V9 Provider 治理迁移。
- 板块分析和催化研究的完整 Use Case/Port/Adapter 收口。
- real-env Profile、Failsafe 五个关键 IT 和一键验收脚本。
- 冻结的真实 RAG/Agent 评测集。
- 完整 Knowledge 管理 Web、前端测试门禁。

因此，**“开始 V3”必须以 V2 指引的完成定义为准，而不能只以文档写完或某一个 Tool 重构完为准。**

### 1.3 V3 开工门禁

满足以下条件后再合并 V3 主体代码：

- V2 的 V9 迁移已经落库，V3 迁移号确定从 V10 开始。
- 三个研究 Tool 已完成分层，Agent Tool 不直接请求第三方站点。
- 默认 verify 全绿，real-env 五个 Suite executed > 0 且 skipped = 0。
- V2 一键验收报告可保存并且没有密钥。
- 当前 Agent、基金查询、RAG 和 SSE 契约已冻结为回归基线。

如果需要并行学习，可以先在独立分支开发 V3 的纯领域模型和计算器，但不要提前合并数据库迁移和安全配置。

## 2. V3 范围

### 2.1 必须完成

1. 注册、登录、刷新、退出、RBAC 和资源所有权校验。
2. 自选分组、自选项、标签、备注和跨设备同步。
3. 用户组合、不可变交易流水、导入批次和持仓重建。
4. 用户真实收益、XIRR、组合风险和暴露覆盖率。
5. 风险问卷与明确保存的用户偏好。
6. 五个个性化只读 Agent Tool。
7. Web 注册登录、自选、流水导入、组合看板和个性化对话闭环。
8. 跨用户越权、重复导入、流水重放和固定样例对账门禁。

### 2.2 明确不做

- 券商、支付平台或银行账户接入。
- 自动申购、赎回、调仓和交易审批。
- 收益保证、基金推荐排名或确定性仓位建议。
- 社交关注、公开组合和排行榜。
- Plan-and-Execute、长期任务恢复和多 Agent；这些属于 V4/V5。
- 复杂企业级 IAM、短信登录、实名认证和找回密码全链路。
- 税务核算；V3 只计算产品与组合研究指标。

## 3. 架构设计

### 3.1 不新增业务 Maven 模块

V3 继续使用现有模块，以包边界组织身份、自选和组合。当前项目规模还没有必要新增 fund-user、fund-portfolio 等模块；过早拆模块会增加装配和循环依赖风险。

建议目录：

~~~text
fund-domain
└─ com.jijing.fund.domain
   ├─ identity
   ├─ watchlist
   └─ portfolio

fund-analytics
└─ com.jijing.fund.analytics.portfolio
   ├─ PortfolioPositionProjector
   ├─ PortfolioValuationCalculator
   ├─ MoneyWeightedReturnCalculator
   ├─ TimeWeightedReturnCalculator
   ├─ PortfolioRiskCalculator
   └─ PortfolioExposureCalculator

fund-application
└─ com.jijing.fund.application
   ├─ auth
   ├─ watchlist
   ├─ portfolio
   └─ personalization

fund-infrastructure
└─ com.jijing.fund.infrastructure
   ├─ security
   ├─ persistence.identity
   ├─ persistence.watchlist
   ├─ persistence.portfolio
   └─ importfile

fund-interface
└─ com.jijing.fund.interfaces.web
   ├─ AuthController
   ├─ WatchlistController
   ├─ PortfolioController
   ├─ PortfolioImportController
   ├─ RiskProfileController
   └─ CurrentUserArgumentResolver
~~~

### 3.2 依赖原则

- Domain 只包含 Java 领域对象、规则和 Repository Port，不导入 Spring、JWT、MyBatis。
- Analytics 继续保持纯 Java；金额一律 BigDecimal，日期使用 LocalDate，时间点使用 Instant。
- Application 编排事务、所有权和用例，不从 SecurityContextHolder 静态取用户。
- Interface 从 Spring Security Principal 取得身份并构造 AuthenticatedUser。
- Infrastructure 实现密码哈希、Token、MyBatis/Jdbc Repository 和文件解析。
- Agent Runtime 只能读取已经验证的 UserContext；Tool 参数不出现 userId。
- Bootstrap 负责 SecurityFilterChain、Bean 和配置属性装配。

### 3.3 统一身份上下文

在 Application 定义中立类型：

~~~java
public record AuthenticatedUser(
        UserId userId,
        Set<UserRole> roles,
        String sessionId) {
}
~~~

Controller 将 Principal 映射为 AuthenticatedUser，再传给用例。不要让 Application 依赖 Jwt、Authentication 或 HttpServletRequest。

所有用户资源用例采用以下形式：

~~~java
PortfolioView getPortfolio(
        AuthenticatedUser actor,
        PortfolioId portfolioId);
~~~

Repository 查询也必须带 owner：

~~~java
Optional<UserPortfolio> findByIdAndOwner(
        PortfolioId portfolioId,
        UserId ownerId);
~~~

不能先 findById，再在 Controller 比较 userId。那会导致遗漏校验、存在性侧信道和未来 Tool 越权。

## 4. 第一阶段：身份认证与资源隔离

### 4.1 技术选择

后端增加：

- fund-bootstrap：spring-boot-starter-security、spring-boot-starter-oauth2-resource-server。
- fund-interface：spring-security-core，只用于 Principal/参数解析类型。
- fund-infrastructure：spring-security-crypto、spring-security-oauth2-jose，用于密码和 Token Adapter。

fund-application 和 fund-domain 不增加 Spring Security 依赖。

Access Token 使用短期 JWT，由 Spring Security Resource Server 验签；Refresh Token 使用高熵随机不透明字符串，只在数据库保存 SHA-256 Hash。

推荐默认值：

| 项目 | 默认 |
|---|---|
| Access Token 有效期 | 15 分钟 |
| Refresh Token 有效期 | 30 天 |
| 密码哈希 | BCrypt，strength 12，可配置 |
| 登录连续失败限制 | 账号/IP 双维度 |
| JWT 签名 | 至少 256-bit 密钥；生产使用密钥管理服务 |
| 时钟偏差 | 60 秒以内 |

开发环境可以使用单服务签发 JWT，但必须通过 Key ID 和配置接口预留轮换能力。密钥不能写入 application.yml、README 或日志。

### 4.2 Token 流程

~~~text
注册/登录
  → 校验账号和密码
  → 签发 15 分钟 Access JWT
  → 生成 Refresh Token，仅把 Hash 入库
  → Refresh Token 放 Secure + HttpOnly + SameSite Cookie

刷新
  → 校验 Cookie、Origin/CSRF、设备会话和 Token Hash
  → 旧 Refresh Token 立即撤销
  → 同一 family 签发新 Token

检测到旧 Token 再次使用
  → 视为泄露
  → 撤销整个 token family
  → 记录安全审计
~~~

Access Token 仅保存在前端内存，不写 localStorage。生产通过同源部署或 BFF 消除宽泛 CORS；开发环境只允许明确的本地 Origin。

### 4.3 用户与角色

角色先固定为：

- USER：使用自选、组合和 Agent。
- ANALYST：在 USER 基础上使用研究数据管理能力。
- ADMIN：用户状态、Knowledge 和治理管理。

不要做让管理员任意查看用户组合内容的“万能查询”。确有运维需求时必须单独授权、填写原因并写 user_audit_log。

### 4.4 API

~~~text
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
GET  /api/v1/users/me
PATCH /api/v1/users/me
~~~

注册请求仅包含 username、displayName、password。V3 不把邮箱验证码作为硬依赖。用户名规范化后唯一，错误响应不能暴露“该用户名是否存在”之外的敏感信息。

### 4.5 安全配置

建议配置项：

~~~yaml
fund:
  security:
    enabled: true
    issuer: jijing-agent
    audience: fund-web
    access-token-ttl: 15m
    refresh-token-ttl: 30d
    bcrypt-strength: 12
    refresh-cookie-secure: true
    allowed-origins: []
~~~

敏感值只从环境变量/密钥管理服务注入：

~~~text
FUND_JWT_SIGNING_KEY
FUND_JWT_KEY_ID
FUND_SECURITY_ALLOWED_ORIGINS
FUND_REFRESH_COOKIE_SECURE
~~~

V3 的安全默认值必须是启用且失败关闭：缺少签名密钥时应用拒绝启动认证能力，不能自动退化成“所有接口 permitAll”。若需要本地演示账号，只能通过显式 local-demo Profile 创建，生产 Profile 禁止种子账号和固定密码。

### 4.6 资源所有权改造

必须把以下资源绑定 userId：

- agent_conversation。
- agent_run 的查询边界。
- agent_fact_card 的读取边界。
- WatchlistGroup/Item。
- UserPortfolio、FundTransaction、ImportBatch、PositionSnapshot。
- RiskProfile。
- 用户导出的报告。

Agent Chat API 的请求体仍然可以带 conversationId，但服务必须查询 conversationId + currentUserId。找不到和不属于当前用户统一返回 404，避免泄露 ID 是否存在。

## 5. 数据库迁移

V2 使用 V9 后，V3 按以下顺序：

### 5.1 V10：身份与会话

~~~text
user_account
role
user_role
refresh_token
~~~

关键字段：

- user_account：user_id、normalized_username、display_name、password_hash、status、token_version、created_at、updated_at、version。
- role：role_code、description。
- user_role：user_id + role_code 联合主键。
- refresh_token：token_id、user_id、family_id、token_hash、expires_at、revoked_at、replaced_by、created_ip_hash、user_agent_hash。

禁止保存明文密码、明文 Refresh Token、完整 IP 和完整 User-Agent。

### 5.2 V11：已有 Agent 数据归属

为 agent_conversation 增加 owner_user_id 和 created_session_id；对 conversationId、owner_user_id 建组合索引。

迁移旧数据采用三步：

1. 创建不可登录的 LEGACY_LOCAL 系统账号。
2. 将已有会话归属到该账号。
3. 把 owner_user_id 改为 NOT NULL。

不要为了迁移方便把旧会话归给第一个注册用户。Agent Run、Message 和 Fact Card 通过 Conversation 外键继承归属，Repository 查询仍必须显式联表或传 owner 条件。

### 5.3 V12：自选

~~~text
watchlist_group
watchlist_item
~~~

约束：

- 同一用户的 normalized group name 唯一。
- 同一分组 fund_code 唯一。
- item 记录 note、tags_json、sort_order、created_at、updated_at、version。
- 每个用户最多 20 组、每组最多 200 个基金；限制由 Application 和数据库共同保护。

### 5.4 V13：组合、流水与导入

~~~text
user_portfolio
fund_transaction
portfolio_import_batch
portfolio_import_row
~~~

fund_transaction 最少包含：

~~~text
transaction_id / portfolio_id / owner_user_id
fund_code / transaction_type
trade_date / confirm_date
shares / gross_amount / fee / confirmed_nav
currency / source / external_reference
idempotency_key / import_batch_id
conversion_group_id / reverses_transaction_id
created_at / created_by
~~~

金额使用 DECIMAL(24,4)，份额和净值使用 DECIMAL(24,8)。禁止 DOUBLE/FLOAT。

唯一约束至少包括：

- owner_user_id + portfolio_id + idempotency_key。
- import_batch_id + source_row_number。
- 外部来源存在稳定编号时，owner_user_id + source + external_reference。

交易流水只允许追加。纠错通过 REVERSAL 或冲正交易表达，不直接 UPDATE 金额和份额，不物理 DELETE。

### 5.5 V14：投影、估值、画像和审计

~~~text
fund_position_snapshot
portfolio_valuation_snapshot
risk_profile
user_audit_log
~~~

快照必须记录：

- as_of_date / calculated_at。
- last_transaction_id。
- input_hash。
- algorithm_version。
- nav_data_version。
- coverage_status。

快照是可重建缓存，不是真相来源。删除快照后必须能够仅依赖流水和历史净值重建。

## 6. 第二阶段：持久化自选

### 6.1 领域对象

~~~java
WatchlistGroup
WatchlistItem
WatchlistTag
~~~

FundCode 复用当前 fund-domain 的值对象。添加自选不要求目标基金已同步到本地，但返回资料时可以标记 PROFILE_NOT_SYNCED。

### 6.2 API 契约

~~~text
GET    /api/v1/watchlists
POST   /api/v1/watchlists
PATCH  /api/v1/watchlists/{groupId}
DELETE /api/v1/watchlists/{groupId}

POST   /api/v1/watchlists/{groupId}/items
PATCH  /api/v1/watchlists/{groupId}/items/{itemId}
DELETE /api/v1/watchlists/{groupId}/items/{itemId}
POST   /api/v1/watchlists/merge-local
~~~

PATCH 和 DELETE 使用 version 或 If-Match 做乐观锁。两个浏览器同时修改时返回 409，而不是后写覆盖前写。

### 6.3 登录后合并本地自选

当前前端自选是内存数组。V3 改造时：

1. 未登录状态允许 localStorage 保存临时基金代码，不保存 Token 和敏感备注。
2. 登录后前端展示合并预览。
3. 用户确认后调用 merge-local。
4. 后端按 fundCode 幂等合并，返回 added、existing、rejected。
5. 成功后再清理本地临时自选。

禁止在登录瞬间静默覆盖用户服务端分组。

## 7. 第三阶段：交易流水与持仓重建

### 7.1 交易类型

V3 支持：

~~~text
SUBSCRIPTION             申购
REDEMPTION               赎回
CASH_DIVIDEND            现金分红
DIVIDEND_REINVESTMENT    红利再投资
CONVERSION_OUT           基金转换转出
CONVERSION_IN            基金转换转入
REVERSAL                 冲正
FEE_ADJUSTMENT           手续费调整
~~~

转换必须以 conversion_group_id 把 OUT/IN 配成一组。缺少一侧时整组拒绝确认。

### 7.2 不可变流水规则

- confirmDate 决定持仓生效顺序；相同日期按 transactionId/sequence 稳定排序。
- shares、amount、fee、nav 的正负方向由 transactionType 决定，API 不接受含糊的任意正负数。
- 申购至少能由 amount、fee、nav 推导 shares，或由来源同时提供确认份额；两者不一致超过容差则拒绝。
- 赎回不能使确认份额小于零。
- 红利再投资增加份额并形成对应成本；现金分红不增加份额。
- 冲正引用原交易，只能冲正一次。
- 所有写操作使用客户端 idempotencyKey。

### 7.3 持仓投影算法

PortfolioPositionProjector 接受按稳定顺序排列的流水，输出：

~~~java
FundPosition {
  fundCode
  confirmedShares
  remainingCost
  averageCostPerShare
  realizedProfit
  accumulatedCashDividend
  lastConfirmedDate
}
~~~

默认成本法使用移动加权平均：

~~~text
申购后剩余成本 = 原剩余成本 + 申购金额 + 手续费
平均成本 = 剩余成本 / 确认份额

赎回分摊成本 = 赎回份额 × 赎回前平均成本
已实现收益 = 赎回到账金额 - 手续费 - 分摊成本
剩余成本 = 原剩余成本 - 分摊成本
~~~

算法版本固定为 portfolio-position-v1，不能在不升级版本的情况下改变舍入、手续费或分红规则。

### 7.4 并发与事务

新增单笔流水：

~~~text
校验 actor 对 portfolio 的所有权
→ 锁定 user_portfolio version
→ 检查 idempotencyKey
→ 追加流水
→ 从最近快照增量重放
→ 写新快照
→ 更新 portfolio version
→ 提交
~~~

同一组合并发写使用乐观锁；冲突返回 409 并允许客户端安全重试。不要使用 JVM 本地锁代替数据库并发控制。

## 8. 第四阶段：CSV/Excel 导入

### 8.1 两阶段导入

~~~text
上传并预检
  → 保存文件 Hash
  → 解析到 staging rows
  → 返回字段映射、有效行和错误行

用户确认
  → 再次核对文件 Hash 和 batch 状态
  → 在一个数据库事务内生成全部流水
  → 重建受影响持仓
  → 标记 batch COMMITTED
~~~

API：

~~~text
POST /api/v1/portfolios/{portfolioId}/imports/preview
GET  /api/v1/portfolios/{portfolioId}/imports/{batchId}
POST /api/v1/portfolios/{portfolioId}/imports/{batchId}/commit
DELETE /api/v1/portfolios/{portfolioId}/imports/{batchId}
~~~

### 8.2 文件安全

- V3 限制 5 MB、2000 行；超出明确拒绝。
- CSV 支持 UTF-8/GB18030 检测，输出统一 UTF-8。
- Excel 仅接受 xlsx；使用 Apache POI 的安全配置限制 Zip Bomb、公式、共享字符串和最大单元格长度。
- 不执行公式、不访问外链、不信任扩展名，校验 MIME 和文件魔数。
- 原文件只保留到批次提交后 24 小时；审计保留 Hash 和统计，不保留敏感明细日志。
- 错误报告只返回当前用户的数据。

### 8.3 模板字段

~~~text
组合名称*
基金代码*
交易类型*
交易日期*
确认日期*
确认份额
交易金额
手续费
确认净值
外部流水号
备注
~~~

预检必须返回 sourceRow、field、errorCode、safeMessage，不能只返回“文件格式错误”。

## 9. 第五阶段：用户收益与组合风险

### 9.1 估值口径

每个日期只能使用该日期当时已确认的份额，以及不晚于该日期的确认净值：

~~~text
positionValue(date) = confirmedShares(date) × confirmedNav(date)
portfolioValue(date) = Σ positionValue + 可确认现金余额
~~~

缺少当日净值时使用最近一个已确认净值并标记 STALE_NAV；超过配置的交易日阈值后该持仓估值变为 UNAVAILABLE，不能静默沿用。

结果必须携带：

- valuationDate。
- navBasis。
- dataCutoff。
- navDataVersion。
- algorithmVersion。
- coverage。
- warnings。

### 9.2 收益指标

必须区分：

| 指标 | 用途 |
|---|---|
| 产品区间收益 | 当前已有，衡量基金净值变化 |
| 持仓未实现收益 | 当前市值减剩余成本 |
| 已实现收益 | 赎回与分红已落袋结果 |
| XIRR/资金加权收益 | 反映用户资金进出时点 |
| TWR/时间加权收益 | 尽量剥离申赎现金流影响 |

MoneyWeightedReturnCalculator 使用确定性的求根算法，限制迭代次数和收益率搜索区间。现金流不足、同号、无解或不收敛时返回 UNAVAILABLE + reason，不返回 0。

### 9.3 风险指标

复用 fund-analytics 已有波动率和最大回撤算法，并增加：

- 组合年化波动率。
- 组合最大回撤及峰谷日期。
- 基金相关性矩阵，按公共实际交易日对齐。
- 单基金集中度、Top-N 集中度和 HHI。
- 基金底层股票持仓重合度。
- 行业/主题暴露和覆盖率。

底层持仓披露可能滞后。暴露结果必须显示 disclosureDate、coveragePercent、providerId 和数据类型，不能包装成实时仓位。

### 9.4 Evidence

个性化指标 Evidence ID 不写死随机 UUID，建议采用可解析形式：

~~~text
PORTFOLIO:{portfolioId}:SNAPSHOT:{asOfDate}:{inputHashPrefix}
TRANSACTION:{transactionId}
NAV:{fundCode}:{navDate}:{dataVersion}
RISK_PROFILE:{profileId}:{version}
~~~

Agent 输出时展示脱敏摘要；完整交易明细只允许当前用户通过 API 查看。

## 10. 第六阶段：风险画像

### 10.1 问卷而不是模型猜测

风险画像由版本化问卷和确定性评分生成：

~~~text
投资期限
收入稳定性
可承受最大回撤
流动性需求
投资经验
目标收益与损失态度
~~~

保存 RiskProfile 时记录 questionnaireVersion、answersHash、score、level、confirmedAt。

风险等级示例：

~~~text
CONSERVATIVE
BALANCED
GROWTH
AGGRESSIVE
~~~

模型可以解释画像，但不能自行修改画像。用户在对话中说“我最近胆子大了”只能成为待确认建议，不能直接写数据库。

### 10.2 API

~~~text
GET  /api/v1/risk-questionnaire
GET  /api/v1/users/me/risk-profile
PUT  /api/v1/users/me/risk-profile
~~~

PUT 必须包含 questionnaireVersion 和全部必填答案，后端重新计算分数，不接受前端直接指定等级。

## 11. 第七阶段：个性化 Agent

### 11.1 身份传播

当前 FundAgentRequest 增加经过认证的用户上下文，但 HTTP 请求体不增加 userId：

~~~java
public record AuthenticatedFundAgentRequest(
        UserId userId,
        String conversationId,
        String message,
        String requestId) {
}
~~~

创建会话时立即写 owner_user_id。聊天、SSE、Fact Card、Run 查询都使用 userId + conversationId。

AgentExecutionTrace 的 ToolContext 增加经过服务端签名/构造的 AuthenticatedUserContext。Tool 只能从 ToolContext 读取，禁止：

- 在模型 Tool Input 中出现 userId。
- 相信提示词里的“请查看另一个用户”。
- 根据 portfolioId 绕过 owner 条件。
- 把用户明细写入公共 RAG 索引。

### 11.2 五个只读 Tool

~~~text
get_my_watchlist
get_my_portfolio
calculate_my_return
analyze_my_portfolio_risk
compare_watchlist_funds
~~~

Tool 责任：

~~~text
校验模型参数
→ 从 ToolContext 取当前用户
→ 调用 Application Use Case
→ 包装 ToolEnvelope、Evidence 和 warning
→ 由 AgentExecutionTrace 审计
~~~

Tool 禁止直接调用 Mapper/JdbcTemplate。V3 个性化 Tool 全部只读；添加自选、写交易、修改画像仍由显式 Web API 完成。

### 11.3 Tool 参数

get_my_portfolio：

~~~text
portfolioId 可选；为空时返回默认组合
asOfDate 可选；默认最近可估值日期
includeTransactions=false
~~~

calculate_my_return：

~~~text
portfolioId
startDate / endDate
method: XIRR | TWR | ABSOLUTE
~~~

analyze_my_portfolio_risk：

~~~text
portfolioId
lookbackDays: 30～1095
includeExposure: true/false
~~~

参数中永远没有 userId。

### 11.4 Prompt 与路由

升级：

~~~text
prompt-version: fund-agent-v3
tool-schema-version: fund-tools-v3
~~~

路由规则增加：

- “我的、自选、持仓、成本、我赚了多少”才允许加载个性化 Tool。
- 普通知识问题不加载用户 Tool，减少隐私暴露和上下文成本。
- 未登录请求访问个性化意图时返回 AUTH_REQUIRED，不让模型编造。
- “帮我买、替我卖、下单”由安全策略拒绝。
- “应该买多少”可以解释风险和现状，但不能输出确定性金额。

### 11.5 个性化评测集

新增：

~~~text
fund-agent-runtime/src/test/resources/evals/
├─ personalization-routing-v3.jsonl
├─ ownership-safety-v3.jsonl
├─ portfolio-evidence-v3.jsonl
└─ suitability-safety-v3.jsonl
~~~

必须包含：

- 同名组合属于不同用户。
- Prompt Injection 要求读取其他用户持仓。
- 模型伪造 userId/portfolioId。
- 缺净值、缺披露持仓和 XIRR 无解。
- “保证收益”“替我下单”“满仓买入”。

## 12. Web 产品闭环

### 12.1 先拆当前单文件页面

当前 page.tsx 同时承担查询、SSE、自选和展示。V3 开始前拆成：

~~~text
fund-web/
├─ app/
│  ├─ login/
│  ├─ register/
│  ├─ watchlists/
│  ├─ portfolios/
│  │  ├─ [portfolioId]/
│  │  └─ import/
│  ├─ agent/
│  └─ knowledge/
├─ components/
│  ├─ auth/
│  ├─ watchlist/
│  ├─ portfolio/
│  ├─ agent/
│  └─ evidence/
└─ lib/
   ├─ api-client.ts
   ├─ auth-session.ts
   ├─ sse-client.ts
   └─ money-format.ts
~~~

### 12.2 页面

- 登录/注册：错误信息、密码规则、会话过期恢复。
- 自选：分组、拖动排序、标签、备注和本地合并预览。
- 组合：总资产、成本、收益、现金流、风险、数据日期和覆盖率。
- 流水：新增、冲正、筛选和分页，不提供直接编辑历史金额。
- 导入：模板下载、上传、字段预览、错误行、确认提交。
- 个性化 Agent：明确展示当前组合/日期，Evidence Drawer 可定位到快照和净值。
- 风险画像：问卷、结果解释、更新时间和重新测评。

### 12.3 前端认证

- Access Token 只保存在内存。
- Refresh Token 由 HttpOnly Cookie 管理。
- api-client 遇到一次 401 时串行刷新，防止并发刷新风暴。
- 刷新失败清理内存状态并跳转登录。
- SSE 使用 fetch + ReadableStream，附带 Authorization；保留当前取消上游和 Run=CANCELLED 语义。
- 不把交易流水、风险答案或 Token 写入浏览器日志。

### 12.4 测试

当前 package.json 只有 lint/build。V3 增加：

- Vitest + React Testing Library：金额展示、导入错误、认证刷新、自选合并。
- MSW：API/SSE 契约模拟。
- Playwright：注册登录、自选、导入、组合查看、个性化 Agent。

CI 命令统一为：

~~~powershell
npm run lint
npm run test:ci
npm run build
npm run test:e2e
~~~

## 13. API 一览

### 13.1 用户与认证

~~~text
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
GET  /api/v1/users/me
PATCH /api/v1/users/me
~~~

### 13.2 自选

~~~text
GET/POST/PATCH/DELETE /api/v1/watchlists...
POST /api/v1/watchlists/merge-local
~~~

### 13.3 组合

~~~text
GET  /api/v1/portfolios
POST /api/v1/portfolios
GET  /api/v1/portfolios/{portfolioId}
PATCH /api/v1/portfolios/{portfolioId}
DELETE /api/v1/portfolios/{portfolioId}

GET  /api/v1/portfolios/{portfolioId}/transactions
POST /api/v1/portfolios/{portfolioId}/transactions
POST /api/v1/portfolios/{portfolioId}/transactions/{transactionId}/reverse

GET  /api/v1/portfolios/{portfolioId}/positions
GET  /api/v1/portfolios/{portfolioId}/valuation
GET  /api/v1/portfolios/{portfolioId}/returns
GET  /api/v1/portfolios/{portfolioId}/risk
GET  /api/v1/portfolios/{portfolioId}/exposure
~~~

分页统一使用稳定排序和 cursor；流水默认按 confirmDate、transactionId 倒序。金额 API 用 JSON 字符串或明确 scale 的 BigDecimal 序列化契约，前端不能依赖 JavaScript Number 做资金运算。

删除组合实际执行 ARCHIVE：组合归档后禁止新增流水，但历史流水、快照、Agent Evidence 和审计继续可追溯；V3 不物理删除包含交易历史的组合。

## 14. 可观测性、隐私与审计

### 14.1 指标

~~~text
auth_login_total{result}
auth_refresh_total{result}
security_access_denied_total{resource}
watchlist_operation_total{operation}
portfolio_transaction_total{type,result}
portfolio_rebuild_duration_seconds
portfolio_import_rows_total{result}
portfolio_valuation_coverage_ratio
personalized_tool_call_total{tool,result}
~~~

标签中禁止 userId、username、portfolioId 和 conversationId，防止高基数与隐私泄露。

### 14.2 审计事件

记录：

- LOGIN_SUCCESS / LOGIN_FAILURE / TOKEN_REUSE_DETECTED。
- WATCHLIST_MUTATED。
- PORTFOLIO_CREATED。
- TRANSACTION_APPENDED / TRANSACTION_REVERSED。
- IMPORT_PREVIEWED / IMPORT_COMMITTED / IMPORT_REJECTED。
- RISK_PROFILE_CONFIRMED。
- CROSS_OWNER_ACCESS_DENIED。
- PERSONAL_DATA_EXPORTED。

审计记录 actorId、action、resourceType、resourceIdHash、requestId、result、safeReason、timestamp。不记录密码、Token、完整请求体和交易备注。

### 14.3 数据删除

V3 至少提供账号停用和数据导出设计。物理删除要考虑流水、审计与 Agent Evidence 的一致性，第一版可采用：

~~~text
账号停用
→ 撤销 Token
→ 禁止登录
→ 异步删除可删除的原始导入文件
→ 个人数据按策略匿名化/清除
→ 安全审计保留不可逆 Hash
~~~

不要用数据库级联直接删除全部 Agent 审计，避免破坏证据完整性。

## 15. 测试策略

### 15.1 Domain / Analytics 单测

- 每种交易类型至少一个正常和一个非法用例。
- 同一流水重复重放结果完全一致。
- 随机合法流水重放后份额不为负，成本守恒。
- 冲正前后投影回到原状态。
- XIRR 固定现金流与 Excel 样例一致。
- XIRR 无解/多解/不收敛返回 UNAVAILABLE。
- TWR 切分现金流区间正确。
- 相关性只使用公共日期。
- 金额舍入在边界值稳定。

### 15.2 Repository / Migration 集成测试

不要用 H2 代替 MySQL 验证 JSON、DECIMAL、唯一键和锁行为。使用专用 MySQL 或 Testcontainers MySQL：

- V1 到 V14 全量迁移。
- V9 到 V14 增量迁移。
- legacy conversation 归属迁移。
- Refresh Token Hash 唯一与轮换。
- 重复 idempotencyKey 只产生一条流水。
- 两个并发事务只能一个更新成功。

### 15.3 越权测试矩阵

为 userA、userB 创建同名/不同 ID 资源，逐项测试：

| 资源 | 读取 | 修改 | 删除 | Agent Tool |
|---|---:|---:|---:|---:|
| Conversation | 404 | 404 | 404 | 拒绝 |
| Watchlist | 404 | 404 | 404 | 不可见 |
| Portfolio | 404 | 404 | 404 | 不可见 |
| Transaction | 404 | 不可编辑 | 404 | 不可见 |
| Import Batch | 404 | 404 | 404 | 不加载 |
| Risk Profile | 404 | 404 | 不开放 | 不可见 |

不能只测试 Controller；至少增加 Application、Repository 和真实 HTTP 三层测试。

### 15.4 安全测试

- 过期、伪造、错误 audience/issuer 的 JWT。
- 被撤销 Refresh Token 和旧 Token 重放。
- 用户禁用后 Access Token 失效策略。
- 登录限流。
- CSRF/Origin。
- CSV Formula Injection。
- xlsx Zip Bomb。
- SQL 注入式筛选参数。
- Prompt Injection 读取其他用户数据。

## 16. 开发迭代

### Iteration 0：V2 收口确认（1～2 天）

- 执行 V2 默认与 real-env 门禁。
- 固定 V9、API、SSE、Agent 和 Web 基线。
- 生成进入 V3 的验收报告。

### Iteration 1：身份与权限（4～6 天）

- V10、Spring Security、注册登录、JWT、Refresh Rotation。
- CurrentUser 映射和 RBAC。
- 登录限流与安全审计。

完成定义：两个用户可以独立登录，Token 轮换和退出失效可测试。

### Iteration 2：存量资源归属（3～4 天）

- V11。
- Agent Conversation/Run/Fact Card 所有权改造。
- Knowledge 管理 API 限制 ANALYST/ADMIN。
- 跨用户 IDOR 集成测试。

完成定义：已知 ID 也不能读取另一个用户的会话或 Run。

### Iteration 3：自选持久化（2～3 天）

- V12、Repository、Use Case、API。
- 本地自选合并。
- get_my_watchlist Tool。

### Iteration 4：流水和持仓投影（5～7 天）

- V13。
- 不可变交易模型、冲正、幂等和并发。
- CSV/xlsx 预检与提交。
- 固定样例和性质测试。

### Iteration 5：估值、收益和风险（5～7 天）

- V14。
- 估值曲线、XIRR、TWR、风险、暴露和覆盖率。
- Excel 对账。
- 快照重建与版本。

### Iteration 6：个性化 Agent（3～5 天）

- 五个只读 Tool。
- 身份 ToolContext、路由、Prompt、安全和 Evidence。
- 个性化评测集。

### Iteration 7：Web 闭环（5～7 天）

- 拆分 page.tsx。
- 登录、自选、组合、导入、画像和 Agent 页面。
- Vitest/MSW/Playwright。

### Iteration 8：验收与文档（2～3 天）

- 安全、故障注入、性能和隐私检查。
- Runbook、演示数据、接口说明、架构图和简历材料。

一名开发者现实预计 7～9 周。若时间有限，V3.0 必须保留身份隔离、流水重建、XIRR、个性化只读 Tool 和越权测试；底层持仓重合度、高级导入映射和数据导出可以放到 V3.1。

## 17. V3 验收清单

### 17.1 身份与安全

- [ ] 密码和 Refresh Token 都不以明文存储。
- [ ] Token 轮换、退出、重放检测和账号停用生效。
- [ ] USER/ANALYST/ADMIN 权限边界明确。
- [ ] userA 无法通过任何 ID 读取 userB 资源。
- [ ] 浏览器 localStorage/sessionStorage 中没有 Token。

### 17.2 自选和组合

- [ ] 自选跨设备保存，本地合并幂等。
- [ ] 流水不可修改，只能追加和冲正。
- [ ] 重复请求/重复导入不产生重复交易。
- [ ] 删除所有快照后可以从流水完整重建。
- [ ] 同一流水重复重放结果完全一致。
- [ ] 固定样例与 Excel 对账通过。

### 17.3 指标

- [ ] 产品收益和用户收益明确区分。
- [ ] XIRR/TWR 的失败状态不会伪装为 0。
- [ ] 估值没有未来数据泄露。
- [ ] 风险和暴露显示日期、版本、覆盖率和限制。

### 17.4 Agent

- [ ] userId 不出现在 Tool Schema。
- [ ] Tool 只从受信任 ToolContext 获取用户。
- [ ] 个性化 Tool 全部只读。
- [ ] 回答中的组合结论可以定位到快照、流水或净值 Evidence。
- [ ] Prompt Injection 无法读取其他用户数据。
- [ ] 买卖、下单、收益保证继续被安全策略拦截。

### 17.5 Web 和工程

- [ ] 用户可完成注册、登录、自选、导入、组合查看和个性化提问。
- [ ] 401 刷新不会产生并发风暴。
- [ ] SSE 取消仍把 Run 标为 CANCELLED。
- [ ] 后端默认/真实门禁和前端 lint/test/build/e2e 全绿。
- [ ] 日志、报告和浏览器控制台没有 Token 或交易明细。

## 18. 推荐验收命令

后端默认：

~~~powershell
$env:JAVA_HOME='你的JDK路径'
mvn verify
~~~

V2 真实能力门禁仍必须执行：

~~~powershell
.\scripts\verify-v2-real-env.ps1
~~~

V3 身份与组合验收建议新增：

~~~powershell
.\scripts\verify-v3-user-portfolio.ps1
~~~

该脚本必须：

1. 使用专用 jijing_agent_test。
2. 执行 V1→V14 和 V9→V14 迁移测试。
3. 创建两个隔离测试用户。
4. 验证 Token 轮换和跨用户拒绝。
5. 导入固定交易样例两次并检查幂等。
6. 删除快照后重建并与基线 Hash 对比。
7. 运行 XIRR/风险 Excel 对账。
8. 运行个性化 Agent Tool 契约与安全评测。
9. 输出 target/v3-acceptance/summary.json，不输出密钥和个人数据。

前端：

~~~powershell
cd fund-web
npm run lint
npm run test:ci
npm run build
npm run test:e2e
~~~

## 19. 简历含金量

完成 V3 后可以真实表达：

> 基于 Spring Security Resource Server 构建多用户基金研究系统，使用短期 JWT 与 Refresh Token Rotation 实现会话安全；以不可变基金交易流水和幂等导入重建持仓，计算 XIRR、TWR、回撤、相关性与暴露覆盖率；将用户组合快照作为带算法版本的 Evidence 注入 Spring AI 只读 Tool，并通过跨租户 IDOR 与 Prompt Injection 测试保障个性化 Agent 的数据隔离。

面试时重点能讲清：

1. 为什么当前持仓是投影，交易流水才是真相。
2. 为什么用户收益不能直接用基金净值涨幅代替。
3. JWT 为什么仍需要 Refresh Token 数据库状态。
4. 为什么 Tool 参数不能让模型传 userId。
5. 为什么组合暴露必须显示披露日期和覆盖率。
6. 如何证明重复导入、并发写和流水重放是确定性的。

## 20. V3 完成定义

只有下面这段话全部成立，V3 才算完成：

> 系统已经具备真实的多用户身份与资源隔离；用户自选和交易流水可持久化、可幂等导入、可审计和可重建；个人持仓、收益、风险与暴露均具有明确日期、数据版本、算法版本和覆盖率；Agent 只能通过服务端注入的当前用户上下文读取只读个性化 Tool，无法访问其他用户数据，也不能执行交易或承诺收益；Web 已形成从登录到组合研究和证据核验的完整闭环；默认、真实环境、越权、安全、对账和端到端门禁都有可保存的验收证据。

完成该状态后，再进入 V4 的混合 Agent Runtime 2.0：执行模式路由、ReAct、Plan-and-Execute、检查点恢复与人工审批。
