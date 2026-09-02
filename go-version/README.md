# FundScope · Go 基金分析 Agent

当前版本是 V1 单机闭环版，面向基金爱好者提供真实基金数据查询、指标分析、组合管理和受控 Agent 能力。

## V1 已实现

- Gin REST API、Request ID、结构化日志、健康检查和 SSE；
- 东方财富公开网页 Provider、标准 HTTP Provider 和 Demo Provider；
- 最新确认净值、历史净值、收益率、波动率、最大回撤和夏普比率；
- 2～10 只基金并发比较；
- SQLite 默认持久化、MySQL 可切换、Redis 可选缓存；
- 自选基金、持仓、组合盈亏、权重和 HHI 集中度；
- OpenAI 兼容模型结构化 Tool Calling，无模型时确定性降级；
- Tool 白名单、有界 DAG、执行审计、证据和 HITL 审批；
- TXT、Markdown、HTML 以及 Tika PDF 文档摄取；
- 中文切块、本地混合检索、用户隔离和 RAG 引用；
- 定时同步、Prometheus 指标、Docker 和内置 Web 页面。

> 历史指标只使用基金公司确认净值。盘中估值不会混入收益和风险计算。本项目不执行交易，不构成投资建议。

## 启动

要求 Go 1.25+。

```powershell
cd D:\jijing-agent\go-version
go mod download
go run ./cmd/server
```

打开 <http://localhost:8080>。默认使用：

```text
Provider = eastmoney
Database = SQLite（data/fund-agent.db）
Redis    = 未配置时自动回退数据库
Model    = 未配置 Key 时使用确定性 Agent
```

Docker 单机版：

```bash
docker compose up --build
```

MySQL、Redis、Tika 组合：

```bash
docker compose -f docker-compose.prod.yml up --build
```

## 关键配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_ADDR` | `:8080` | HTTP 地址 |
| `FUND_PROVIDER_TYPE` | `eastmoney` | `eastmoney/demo/http` |
| `DATABASE_DRIVER` | `sqlite` | `sqlite/mysql/memory` |
| `DATABASE_DSN` | 本地 SQLite | 数据库连接串 |
| `REDIS_ADDRESS` | 空 | Redis 地址，故障时降级 |
| `APP_AUTH_TOKEN` | 空 | 单机 Bearer Token |
| `MODEL_BASE_URL` | OpenAI API | OpenAI 兼容模型地址 |
| `MODEL_API_KEY` | 空 | 模型 Key |
| `MODEL_NAME` | `gpt-4.1-mini` | 模型名称 |
| `TIKA_URL` | 空 | PDF 解析服务地址 |
| `SYNC_INTERVAL` | `12h` | 自选基金同步周期 |

程序不会自动加载 `.env` 文件。完整配置见 [.env.example](.env.example)。

## Agent 工具

只读工具：

```text
get_fund_profile
get_fund_nav
calculate_fund_metrics
compare_funds
analyze_portfolio
search_fund_documents
```

写工具 `add_watchlist`、`save_position` 只创建审批单，批准后才会执行。

## 主要 API

| 路径 | 用途 |
| --- | --- |
| `GET /api/v1/funds/:code/quote` | 最新确认净值/可用时估值 |
| `GET /api/v1/funds/:code/nav` | 历史净值 |
| `GET /api/v1/funds/:code/metrics` | 收益风险指标 |
| `POST /api/v1/fund-comparisons` | 多基金比较 |
| `/api/v1/watchlist` | 自选管理 |
| `/api/v1/portfolio/positions` | 持仓管理 |
| `GET /api/v1/portfolio/analysis` | 组合分析 |
| `/api/v1/knowledge/documents` | 文档摄取 |
| `POST /api/v1/knowledge/search` | 知识检索 |
| `POST /api/v1/agent/chat` | Agent 对话 |
| `POST /api/v1/agent/chat/stream` | SSE Agent 对话 |
| `/api/v1/agent/approvals` | HITL 审批 |
| `GET /metrics` | Prometheus 指标 |

## 验证

```bash
go fmt ./...
go vet ./...
go test ./...
go test -race ./...
```

`go test -race` 需要 CGO 和 C 编译器。

总体设计见 [Go 版总体开发指引](../docs/00-Go版基金Agent总体开发指引.md)，后续版本见 [V2～V6 开发大纲](../docs/07-Go版后续版本开发大纲.md)。
