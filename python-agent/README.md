# FundPilot Python Research Agent

这个服务负责首页问答、研究任务、月报与 Agent 事件判断，使用 FastAPI、LangChain 和 LangGraph。Java 继续提供登录、基金、自选、组合等业务 API。旧 Java Agent 源码和接口保留；知识库模块本轮不改。

核心结构与学习顺序见 [迁移与阅读指南](../docs/python-agent-migration.md)。原有双基金研究图也保留：看多与看空并行、裁决等待二者完成。首页通用图则采用“分析取数循环 → 风险复核 → 综合回答 → 引用检查”，需要导出时再进入检查点审批。

## 启动

先启动项目根目录的 Java 后端（默认 8080），再从 `python-agent` 目录运行：

```powershell
uv sync
$env:FUND_API_BASE_URL = 'http://127.0.0.1:8080'
# 复用你本机已有的 DASHSCOPE_API_KEY；当前终端没有时再设置它。
# $env:DASHSCOPE_API_KEY = '你的百炼密钥'
uv run uvicorn app:app --reload --port 8001
```

IDE 里也可以直接运行 `main.py`，但解释器要选 `python-agent/.venv/Scripts/python.exe`，不要选系统安装的 Python 3.13。直接运行 `main.py` 现在会启动 8001 的 Python HTTP 服务；旧的净值演示不再在启动时自动请求 Java。打开 `http://127.0.0.1:8001/docs` 可确认 Python 服务已启动。Python 可以先启动，但执行基金研究时 Java 8080 必须可用。

默认模型分别为 `qwen3.8-flash`、`qwen3.7-plus`、`qwen3.8-max`。通用图对应分析员、风险复核员、综合员；双基金图对应看多、看空、裁决。三个角色使用同一个 `DASHSCOPE_API_KEY`。分别设置 `RESEARCH_BULL_MODEL`、`RESEARCH_BEAR_MODEL`、`RESEARCH_JUDGE_MODEL` 可调整模型；其他地域可设置 `RESEARCH_BASE_URL`。也支持优先使用 `RESEARCH_API_KEY`。请求关闭思考模式，双基金图要求 JSON 输出，通用图使用工具调用和带引用的中文回答。

研究图给模型展示 `E01` 等短证据编号，收到观点后由 Python 映射回包含基金、区间、口径及数据版本的完整编号，并校验每条引用。这样模型无需逐字复制很长的编号，保存的结果仍能追溯到原始快照。

浏览器打开 `http://127.0.0.1:8001/docs`，调用 `POST /api/research/compare`：

```json
{
  "fundCodes": ["000001", "110022"],
  "startDate": "2026-01-01",
  "endDate": "2026-09-29"
}
```

结果包含 `runId`、同区间指标快照、两位分析师的结构化观点、裁决和各节点耗时。成功运行会保存到本地 SQLite，可用 `GET /api/research/runs/{runId}` 查询。`AGENT_DB_PATH` 可指定数据库文件路径。

前端的 `/arena` 页面使用 `POST /api/research/compare/stream`。这个接口返回 SSE 事件：`started` 给出运行编号，四次 `node_completed` 给出已完成节点的输出和耗时，最后 `completed` 给出完整结果，失败时发 `failed`。看多和看空是并行节点，二者的完成顺序不固定。

本地开发时，另开终端在 `fund-web` 目录运行 `npm run dev`。Vite 会将 `/api/research` 转发到 Python 服务的 `http://127.0.0.1:8001`；如端口不同，可设置 `PYTHON_AGENT_PROXY`。原有 `/api/v1` 仍转发到 Java 服务。这个页面依赖本地 Java、Python 和模型服务同时可用。开始研究前须在同一个前端地址登录；页面把本次登录令牌交给 Python，Python 再转发给 Java 的基金比较接口。未登录或登录过期时会显示登录提示。

没有模型配置时返回 503；Java 服务不可用或数据格式不正确时返回 502。通用图可读取当前登录用户的组合和自选；所有会话、运行和报告均按 Java 验证的用户 ID 隔离。

## 统一 Agent 接口

- `/api/agent/conversations`：创建会话。
- `/api/agent/chat/stream`：流式展示运行事件及最终回答。
- `/api/agent/runs`：后台研究及历史；支持计划、事件、取消、报告和运行摘要。
- `/api/agent/reports/monthly`：指定月份、组合或自选分组生成月报，范围由服务端工具层强制限制。
- `/api/agent/runs/{id}/approvals/{approvalId}`：确认导出，LangGraph 从 SQLite 检查点继续；加 `/reject` 则拒绝。
- `/api/agent/runs/{id}/export`：审批成功后返回可下载 Markdown 正文。

前端保留原来的首页、研究任务与月报页面，这些页面现在默认走 `/api/agent`，本地 Vite 将它转发到 8001。旧 Java 运行不混进 Python 历史，原记录与接口仍保留。工具治理管理员页仍显示旧 Java MCP 工具，尚未代表 Python 工具注册表。

当前 SQLite 运行时按**一个 Uvicorn 进程**启动，不使用 `--workers`。普通运行重启后会标成失败，可重新提交；等待审批的运行能利用检查点跨重启继续。凭证只留在内存，恢复审批时使用本次登录令牌。网页断线不会取消后台研究，取消需明确调用接口。

## Java 事件桥接与部署

Java 的 Outbox 默认把事件交给 Python `/internal/agent/events` 判断；Java 仍负责业务事务和通知保存。Python 不可用时事件十秒后重试，业务写入不会因此回滚。设置 `fund.agent.execution-backend=java` 可恢复旧分发器用于对照。

本地 Python 自动生成被 Git 忽略的 `data/agent-event.key`，Java 会从工作区读取它；它是独立的事件凭证，不是模型密钥。自定义数据库路径或远程部署时，两边配置相同的 `AGENT_EVENT_SECRET`，或给 Java 配 `fund.agent.event-secret-file`。不要把事件凭证放进前端。

生产部署需要网关分别转发 `/api/agent`、`/api/research` 到 Python，业务 `/api/v1` 到 Java；Vite 的开发代理不会进入生产部署。也可以配置前端 `NEXT_PUBLIC_AGENT_API_BASE`，并设置 Python `AGENT_CORS_ORIGINS` 为明确的前端来源。事件内部路由供 Java 调用，不作为浏览器公开入口。

## Tavily MCP：联网搜索与原文片段

新增 `tavily_search` 与 `tavily_extract`，实际连接官方远端 `https://mcp.tavily.com/mcp/`，使用 MCP SDK 的 Streamable HTTP。每次执行先初始化会话、读取工具清单、校验远端 Schema，然后调用工具；搜索与提取结果沿用现有证据、耗时和引用流程。

Tavily 使用独立密钥，百炼密钥不能代用。在 `python-agent/.env` 写入 `TAVILY_API_KEY`；可以参考 `.env.example`，真实 `.env` 被 Git 忽略。终端已有环境变量优先。密钥只通过 Tavily 的 Authorization 请求头发送，不进入 URL、模型提示词、前端或运行记录。`TAVILY_ENABLED=false` 可以关闭联网工具。未配置时不把这两个工具列给模型，其余研究功能继续可用。

```powershell
uv sync
# 本地配置 .env 后，验证一次真实 MCP 调用：
uv run python scripts/check_tavily.py
uv run python main.py
```

首次只开放搜索和提取：搜索固定基础深度、最多五条结果；提取最多两个公开网页。网页片段截取会明确标注，未知发布日期保持为空。首页点击 `[E01]` 可查看每个来源链接、摘要或原文片段。月报暂不开放联网工具，避免破坏已固定的历史研究范围。现有知识库模块保持原样。

MCP 不是只把 Schema 发给模型：本地 `StructuredTool` 提供经筛选的中文参数说明，模型选择工具后，Python 才进行 MCP 协议交互，并将真实结果作为 `ToolMessage` 送回模型。这次未开放全站爬取或 Tavily 自带 Research，避免增加不必要的调用范围与耗时。每次调用建立独立会话；未来可在实测需要时优化连接复用。

## 自动化测试

```powershell
uv run pytest
```

测试使用假模型与 HTTP Mock，不需要真实模型密钥或 Java 服务。
