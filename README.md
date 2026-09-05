<div align="center">

# FundPilot

### 你的基金研究与组合管理工作台

查基金 · 看走势 · 比较表现 · 管理持仓 · 带着证据问 AI

把基金数据、个人组合和研究资料放在一起，让每一次分析都有据可查。

[能帮你做什么](#能帮你做什么) · [环境准备](#环境准备) · [部署启动](#部署启动) · [开启-ai-问答](#开启-ai-问答) · [常见问题](#常见问题)

</div>

---

## 能帮你做什么

| 你想做的事 | FundPilot 如何帮你 |
| --- | --- |
| **快速了解一只基金** | 输入 6 位基金代码，查看基金概况、基金经理、历史净值和走势图。 |
| **比较几只基金的表现** | 在同一时间区间比较 2～10 只基金，查看收益、波动、最大回撤等指标，减少比较口径不同带来的误判。 |
| **整理自己的关注清单** | 将感兴趣的基金加入自选；登录后管理个人自选列表。 |
| **看清自己的持仓与收益** | 创建组合，导入 CSV / Excel 交易流水，先预检再确认导入，查看重建后的持仓和收益。 |
| **用自然语言做研究** | 向 AI 询问基金表现、风险或基金间的差异，边看回答边查看研究过程、数据来源和引用依据。需要配置对话模型。 |
| **从资料中寻找答案** | 接入 PDF、HTML、TXT 研究资料，让 AI 检索相关内容并引用文档证据。需要开启知识库，由有权限的账号上传资料。 |

你可以这样开始一次研究：

> “帮我看看 000001 在 2026 年 1 月的净值变化和最大回撤。”
>
> “把这两只基金放在同一时间段比较，说明收益和波动的差异。”
>
> “根据已上传的基金报告，解释它的投资范围，并给出资料出处。”

回答的完整程度取决于已接入的数据和资料。数据不足时会说明缺口；基金历史表现与个人组合收益也会分别计算和展示。

## 环境准备

| 环境 | 版本 / 要求 | 用途 |
| --- | --- | --- |
| Java JDK | **21+**，编译目标为 Java 21 | 运行后端 |
| Maven | **3.9+** | 编译、测试和打包后端 |
| MySQL | **8+** | 保存基金数据、账号、组合和研究记录 |
| Node.js | **22.13.0+**，附带 npm | 安装依赖、构建和运行前端 |
| Redis | 可选；Compose 提供 **7.4** | 缓存；不可用时查询可降级 |
| Docker Desktop / Docker Compose | 可选 | 启动 Redis、Elasticsearch 等依赖服务 |
| 对话模型服务 | 可选，支持 OpenAI 兼容接口 | 开启 AI 问答 |
| 向量模型服务 | 使用知识库时需要 | 检索研究资料 |
| Elasticsearch | 可选；版本以 `docker-compose.yml` 为准 | 使用持久化的文档检索索引 |

**先体验基础功能，只需 Java、Maven、MySQL 和 Node.js。** 不配置模型密钥也能查基金、看净值、管理自选与组合。

默认使用 **Mock 演示数据**，AI 和知识库默认关闭。想查看真实基金数据，可在启动前按下文切换数据源。

## 部署启动

以下命令以 **Windows PowerShell** 为例，默认从项目根目录执行。前后端分别占用一个终端。

### 1. 创建数据库

确认本机 MySQL 已启动，进入客户端：

```powershell
mysql -u root -p
```

在 MySQL 提示符中执行：

```sql
SOURCE scripts/init-local-mysql.sql;
EXIT;
```

脚本会创建业务库 `jijing_agent` 和测试库 `jijing_agent_test`；后端首次启动时会自动创建和升级业务表。

### 2. 配置后端环境

在准备启动后端的 PowerShell 终端中设置：

```powershell
$env:MYSQL_USERNAME = 'root'
$env:MYSQL_PASSWORD = '你的 MySQL 密码'

# 生成本机登录签名密钥；后续启动请复用并妥善保存。
$env:FUND_JWT_SIGNING_KEY = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')

# 本机使用 HTTP 时关闭 Secure Cookie；部署到 HTTPS 时应设回 true。
$env:FUND_SECURITY_REFRESH_COOKIE_SECURE = 'false'

# 明确使用演示模式，便于首次启动。
$env:FUND_PROVIDER_TYPE = 'mock'
$env:FUND_AGENT_ENABLED = 'false'
$env:AI_CHAT_PROVIDER = 'none'
$env:FUND_KNOWLEDGE_ENABLED = 'false'
$env:AI_EMBEDDING_PROVIDER = 'none'
```

默认连接 `127.0.0.1:3306/jijing_agent`。使用其他数据库地址时，额外设置：

```powershell
$env:MYSQL_URL = 'jdbc:mysql://127.0.0.1:3306/jijing_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
```

> 环境变量只对当前终端及其启动的进程生效。根目录的 `.env.example` 是配置参考，Java 启动命令不会自动读取 `.env`。也可使用被 Git 忽略的 `application-local-private.yml` 保存本机配置，环境变量优先。

如需 Redis，在项目根目录执行：

```powershell
docker compose up -d redis
```

业务 MySQL 需要自行准备；Compose 中的 `mysql-test` 仅用于独立的集成测试。

### 3. 打包并启动后端

```powershell
mvn -gs .mvn/settings-global-public.xml clean verify
java -jar fund-bootstrap/target/fund-bootstrap-0.1.0-SNAPSHOT.jar
```

后端默认监听 **8080**。另开终端检查服务：

```powershell
Invoke-RestMethod 'http://localhost:8080/actuator/health'
```

返回 `status: UP` 表示健康检查通过。

### 4. 启动前端工作台

在新的 PowerShell 终端中执行：

```powershell
cd fund-web
npm ci
$env:API_PROXY = 'http://127.0.0.1:8080'
npm run dev -- --port 3000
```

打开 **[http://localhost:3000](http://localhost:3000)**。

前端开发服务会把 API 请求转发到后端。后端地址或端口发生变化时，同步修改 `API_PROXY`。

### 5. 开始使用

1. 在首页输入 `000001`，查看基金概况与净值走势。首次查询缺少本地数据时会尝试从当前数据源获取。
2. 注册并登录账号，创建个人自选和组合。
3. 在组合页面上传 CSV / Excel 流水，查看预检结果后确认导入。
4. 如需自然语言研究，按下一节配置模型，然后重启后端。

<details>
<summary><strong>在服务器上运行</strong></summary>

后端使用同一份 JAR；服务器设置好数据库、签名密钥和所需模型环境变量后，通过进程管理服务持续运行。默认 `local` 配置会读取前文列出的环境变量；若自行切换 Spring profile，需要同时提供对应的数据源配置。

前端可先构建，再启动生产服务：

```powershell
cd fund-web
npm ci
$env:API_PROXY = 'http://127.0.0.1:8080'
npm run build
npm run start -- --port 3000
```

对外提供服务时，使用 HTTPS 反向代理统一入口：页面请求转发到前端，`/api/` 请求转发到后端；AI 流式回答需要关闭代理响应缓冲，并设置足够的读取超时。

| 部署项 | 配置说明 |
| --- | --- |
| 登录密钥 | `FUND_JWT_SIGNING_KEY` 至少 32 个字符，固定保存，多实例保持一致 |
| HTTPS 登录 | `FUND_SECURITY_REFRESH_COOKIE_SECURE=true` |
| 允许访问的前端域名 | `FUND_WEB_CORS_ALLOWED_ORIGINS=https://你的域名` |
| 运维接口 | `/internal/` 供研究员或管理员使用；评测接口需替换 `FUND_AGENT_EVAL_TOKEN` 默认值 |
| 数据保存 | 持久保存并备份 MySQL；启用知识库时同时保存原始资料目录和 Elasticsearch 数据卷 |

仓库中的 Compose 用于启动依赖服务，前端和 Java 后端仍需分别部署。

</details>

## 开启 AI 问答

在后端终端配置 OpenAI 兼容的对话服务，然后重新运行 JAR：

```powershell
$env:FUND_AGENT_ENABLED = 'true'
$env:AI_CHAT_PROVIDER = 'openai'
$env:AI_CHAT_API_KEY = '你的对话模型密钥'
$env:AI_CHAT_BASE_URL = 'https://你的模型服务地址'
$env:AI_CHAT_MODEL = '你的对话模型名称'
```

登录工作台后即可发起研究问答。模型需要支持工具调用；服务地址按供应商的 OpenAI 兼容配置填写。

## 使用真实基金数据

在后端启动前选择一种数据源，修改后重启。切换数据源不会自动替换已经入库的历史数据；已有记录需要通过同步接口更新。

| 模式 | 适合用途 | 配置 |
| --- | --- | --- |
| `mock` | 本地体验、接口联调 | 默认选项，无需外部数据密钥 |
| `eastmoney` | 个人学习与演示 | 使用内置东方财富适配，需能访问外部服务 |
| `real` | 接入自有或授权数据服务 | 按 [HTTP Provider 契约](docs/provider-http-contract.md) 配置服务地址与密钥 |

例如，切换东方财富数据：

```powershell
$env:FUND_PROVIDER_TYPE = 'eastmoney'
$env:FUND_DATA_BASE_URL = 'https://fundmobapi.eastmoney.com'
```

同步入口为 `POST /internal/v1/funds/{fundCode}/sync?startDate=2026-01-01&endDate=2026-01-31`，需要携带具备 `ANALYST` 或 `ADMIN` 角色的登录令牌；普通注册账号不能调用。

## 接入研究资料

在已开启 AI 的基础上，再配置向量模型和文档处理任务：

```powershell
$env:FUND_KNOWLEDGE_ENABLED = 'true'
$env:FUND_KNOWLEDGE_WORKER_ENABLED = 'true'
$env:AI_EMBEDDING_PROVIDER = 'openai'
$env:AI_EMBEDDING_API_KEY = '你的向量模型密钥'
$env:AI_EMBEDDING_BASE_URL = 'https://你的向量模型服务地址'
$env:AI_EMBEDDING_MODEL = '你的向量模型名称'
$env:AI_EMBEDDING_DIMENSIONS = '1536'
$env:FUND_KNOWLEDGE_INDEX_TYPE = 'local'
```

`AI_EMBEDDING_DIMENSIONS` 必须与模型实际输出维度一致。`local` 使用内存索引，适合联调，进程重启后索引不会保留；需要持久保存索引时使用 Elasticsearch：

```powershell
docker compose --profile knowledge up -d elasticsearch
$env:FUND_KNOWLEDGE_INDEX_TYPE = 'elasticsearch'
$env:ELASTICSEARCH_URI = 'http://127.0.0.1:9200'
```

重启后端后，由具备 `ANALYST` 或 `ADMIN` 角色的账号通过 `POST /internal/v1/knowledge/documents` 上传资料。接口接收文件及标题、文档类型等信息；文档处理完成后，AI 才能检索其中内容。

## 常见问题

| 问题 | 处理方式 |
| --- | --- |
| 后端提示签名密钥长度不足 | 设置至少 32 个字符的 `FUND_JWT_SIGNING_KEY`，在同一终端重新启动。 |
| MySQL 连接失败 | 检查服务、端口、数据库名、账号密码，以及业务库是否已创建。 |
| 网页提示服务返回非 JSON | 确认后端已启动，检查前端 `API_PROXY` 是否指向正确地址。 |
| 本地登录后无法续期 | HTTP 环境设置 `FUND_SECURITY_REFRESH_COOKIE_SECURE=false`，重启后重新登录。 |
| AI 不可用 | 检查是否开启 Agent，并配置支持工具调用的模型、密钥和服务地址。 |
| 接口返回 401 / 403 | 先登录；同步和文档管理接口还需要研究员或管理员角色。 |
| 看到的数据与真实行情不同 | 默认是 Mock 数据；检查当前数据源及已入库记录是否已经更新。 |
| 指标显示不可用或覆盖率未知 | 检查净值区间和样本数量；未导入交易日历时覆盖率为 `UNKNOWN`。 |

## 更多文档

- [运行与验收手册](docs/Phase4B-运行验收手册.md)
- [外部基金数据接入契约](docs/provider-http-contract.md)
- [Agent 运行说明](fund-agent-runtime/README.md)
- [知识库说明](fund-knowledge/README.md)
