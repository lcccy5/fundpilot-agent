# FundPilot 基金投研 Agent

FundPilot 是基于 Java 21、Spring Boot、Spring AI 与 LangGraph4j 构建的基金投研 Agent。系统采用自研持久化 DAG Runtime 与可恢复研究子图的双层编排架构，覆盖 Hybrid RAG、Claim 级证据校验、多用户 Tool 权限隔离、组合分析和运行审计。

## 已实现

- Maven 多模块分层：interface / application / domain / infrastructure / agent-runtime / scheduler / test-support / bootstrap。
- 两个可运行的基金查询 API，默认使用稳定的 Mock 数据。
- 6 位基金代码校验、统一响应、统一异常、`X-Request-Id` 链路标识。
- 本机 MySQL + Flyway 数据库迁移配置；MySQL 不使用 Docker。
- Docker Compose 提供 Redis，以及通过 `knowledge` profile 按需启动的本地 Elasticsearch。
- Actuator 健康检查与 Prometheus 指标端点。
- MyBatis-Plus 基金与历史净值 Repository、Flyway V2/V3 表结构。
- Mock/HTTP 外部数据 Provider、数据质量校验、同步审计和幂等 upsert。
- Redis Cache Aside、Redis 故障降级、本地锁/Redisson 分布式锁。
- Resilience4j 重试、限流、熔断及定时增量同步入口。
- 独立 `fund-analytics` 纯 Java 模块，不依赖 Spring 和数据库，可单独测试核心公式。
- 累计/年化收益、样本波动率、最大回撤及峰谷恢复日、夏普比率、上涨日占比和极值收益。
- 单基金任意区间指标 API，以及 2～10 只基金公共净值区间对齐和稳定排名 API。
- 单位净值、累计净值、复权净值三种口径边界；确认/估算/修正净值状态模型。
- 交易日历覆盖率、样本不足与零波动显式 `UNAVAILABLE`，不伪造数值 0。
- 基金数据修订版本、算法版本和 Redis 版本化 Key，数据变化后不复用旧指标。
- MySQL 指标快照表和工作日 03:30 可开关的标准周期预计算任务。
- Spring AI `ChatClient`、模型适配、会话记忆和声明式只读 Tool Calling。
- Token 预算型会话窗口和独立短期 Fact Card 记忆；跨轮复用仍保留完整 evidenceId、来源与有效期。
- 自研基金 Agent 执行边界：动态工具路由、轮次/工具次数/重复调用限制、总超时。
- 基金概况、净值、指标和公平比较四类核心工具通过 Application Use Case 访问业务能力，不直接读数据库。
- 每次回答携带证据 ID、数据来源/版本和局限，运行、工具调用及错误持久化审计。
- Java 前后置安全策略，拦截提示词窃取、危险操作、收益保证和确定性仓位建议。
- Agent REST/SSE API 和 Micrometer 运行次数、耗时指标；未配置模型时默认安全关闭。
- 独立 `fund-knowledge` 纯 Java 模块，包含文档状态机、中文结构化切块、RRF 和上下文预算。
- PDF/HTML/Text 安全解析、本地原始文件存储、V6 文档/版本/摄取任务元数据。
- 本地内存索引和 Elasticsearch REST 双适配，支持 BM25 + dense-vector kNN 两路召回。
- 文档检索 Tool、事实 Tool/RAG 混合路由、页码证据以及伪造引用拦截。
- 50 条 Agent 路由回归集、真正增量 SSE、单 Tool 超时和可选真实模型 Smoke Test。
- V7 MySQL 租约任务、异步文档摄取、解析/切块/Embedding 批次断点恢复和失败重试。
- 文档、版本、任务管理 API，以及 Elasticsearch 物理索引重建、别名原子切换与回滚。
- Embedding 维度保护、Elasticsearch 认证/超时/批量部分失败检测和相邻 Chunk 合并。
- Claim 级证据类型校验、整句缓冲 SSE、安全文档样本和确定性 RAG 质量门禁 Runner。
- 东方财富真实基金资料/历史净值适配，以及关联 ETF 代理实时行情。
- 板块 ETF 情景分析、基金持仓/PCF 穿透、产业链映射、公告核验和催化影响评估。
- `fund-web` FundPilot 工作台：基金查询、净值图、自选演示、Agent SSE 轨迹和证据化富文本回答。

## 前置条件

- JDK 21+（本项目以 Java 21 字节码编译）
- Maven 3.9+
- 本机 MySQL 8+
- 可选：Docker Desktop（仅运行 Redis）

## 首次启动

1. 创建本机数据库：在 PowerShell 执行 `mysql -u root -p < scripts/init-local-mysql.sql`。
2. 设置当前终端的数据库密码：`$env:MYSQL_PASSWORD = '你的本机MySQL密码'`。
3. 可选启动 Redis：`docker compose up -d redis`。
4. 编译和测试：`mvn -gs .mvn/settings-global-public.xml test`。
5. 打包：`mvn -gs .mvn/settings-global-public.xml clean verify`。
6. 启动：`java -jar fund-bootstrap/target/fund-bootstrap-0.1.0-SNAPSHOT.jar`。

启动后访问：

- 首次使用 Mock 数据同步：`POST http://localhost:8080/internal/v1/funds/000001/sync?startDate=2026-01-01&endDate=2026-01-31`
- `GET http://localhost:8080/api/v1/funds/000001`
- `GET http://localhost:8080/api/v1/funds/000001/nav?startDate=2026-01-01&endDate=2026-01-31`
- `GET http://localhost:8080/api/v1/funds/000001/metrics?startDate=2026-01-01&endDate=2026-01-31&navBasis=ACCUMULATED_NAV`
- `POST http://localhost:8080/api/v1/fund-comparisons`，请求体包含 2～10 个基金代码、起止日期和净值口径。
- `GET http://localhost:8080/actuator/health`
- `GET http://localhost:8080/actuator/prometheus`

Agent 默认关闭，因此正常开发基金数据能力不需要大模型 Key。启用 OpenAI 兼容模型时，在当前终端设置：

```powershell
$env:FUND_AGENT_ENABLED = 'true'
$env:AI_CHAT_PROVIDER = 'openai'
$env:AI_API_KEY = '你的模型密钥'
$env:AI_BASE_URL = '兼容服务地址'
$env:AI_CHAT_MODEL = '模型名称'
```

然后依次调用 `POST /api/v1/agent/conversations` 创建会话，再调用 `POST /api/v1/agent/chat`；流式协议入口为 `POST /api/v1/agent/chat/stream`。SSE 已输出真实增量 `answer.delta`，客户端取消订阅会取消上游并把 Run 标记为 `CANCELLED`。

也可以在项目根目录创建已被 Git 忽略的 `application-local-private.yml` 保存本机密钥；`application-local.yml` 会自动加载它。环境变量优先于该私有文件，因此部署环境仍建议用环境变量或密钥管理服务。

知识库默认关闭。学习和本地接口联调可使用内存索引：

```powershell
$env:FUND_KNOWLEDGE_ENABLED = 'true'
$env:AI_EMBEDDING_PROVIDER = 'openai'
$env:AI_API_KEY = '你的模型密钥'
$env:AI_EMBEDDING_MODEL = '你的向量模型'
$env:AI_EMBEDDING_DIMENSIONS = '1536'
$env:FUND_KNOWLEDGE_INDEX_TYPE = 'local'
```

真实混合检索使用 Elasticsearch：`docker compose --profile knowledge up -d elasticsearch`，然后设置 `$env:FUND_KNOWLEDGE_INDEX_TYPE = 'elasticsearch'`。MySQL 仍直接使用本机安装，Docker Compose 不包含 MySQL。

知识库管理入口：

- `POST /internal/v1/knowledge/documents`：上传 PDF、HTML 或 TXT，单文件最大 50 MB。
- `POST /internal/v1/knowledge/search/debug`：调试 BM25 + 向量 RRF 检索结果。
- Agent 开启知识库后会按问题动态获得 `search_fund_documents` Tool。

默认外部数据源为 `mock`，但查询统一读取 MySQL/Redis。切换真实数据源前需按 [HTTP Provider 契约](docs/provider-http-contract.md)配置适配服务、`FUND_DATA_BASE_URL`、`FUND_DATA_API_KEY` 和 `FUND_PROVIDER_TYPE=real`。

本机 MySQL 集成测试默认跳过，避免误操作开发库。需要验证时设置 `RUN_MYSQL_INTEGRATION_TESTS=true`，测试会强制校验数据库名必须为 `jijing_agent_test`。

交易日历表在 Phase 2 已建好，但日历数据必须来自可追踪的真实数据源，项目不会拿“周一到周五”冒充中国交易日。未导入日历时覆盖率返回 `UNKNOWN`，收益与回撤仍可计算，接口不会中断。

## 相关文档

- [运行与验收手册](docs/Phase4B-运行验收手册.md)
- [外部基金数据 Provider 契约](docs/provider-http-contract.md)
