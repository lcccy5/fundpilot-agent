# FundPilot Python Research Agent

这个服务复用 Java 后端的基金比较结果，用 LangGraph 组织四个节点：数据读取、看多分析、看空分析、独立裁决。两位分析师并行运行；模型输出必须引用本次比较快照中的证据 ID。

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

默认模型分工：看多 `qwen3.8-flash`，看空 `qwen3.7-plus`，裁决 `qwen3.8-max`。三个角色使用同一个 `DASHSCOPE_API_KEY`，数据节点不调用模型。需要调整时分别设置 `RESEARCH_BULL_MODEL`、`RESEARCH_BEAR_MODEL`、`RESEARCH_JUDGE_MODEL`；其他百炼地域可设置 `RESEARCH_BASE_URL`。也支持单独使用 `RESEARCH_API_KEY`，它优先于 `DASHSCOPE_API_KEY`。模型请求关闭思考模式并要求 JSON 输出，方便结构化校验。

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

本地开发时，另开终端在 `fund-web` 目录运行 `npm run dev`。Vite 会将 `/api/research` 转发到 Python 服务的 `http://127.0.0.1:8001`；如端口不同，可设置 `PYTHON_AGENT_PROXY`。原有 `/api/v1` 仍转发到 Java 服务。这个页面依赖本地 Java、Python 和模型服务同时可用。

没有模型配置时返回 503；Java 服务不可用或数据格式不正确时返回 502。接口仅分析公开基金数据，不读取个人组合。

## 测试

```powershell
uv run pytest
```

测试使用假模型与 HTTP Mock，不需要真实模型密钥或 Java 服务。
