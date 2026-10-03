# Agent 迁移与 Python 阅读指南

## 本轮范围

Java 继续提供登录、基金、自选、组合、业务事件和通知存储。Python 自行负责模型决策、工具选择、风险复核、引用检查、运行持久化与事件判断。TypeScript 页面默认使用 `/api/agent/**`，没有转发旧 Java Agent 来执行。

旧 `fund-agent-runtime` 源码、接口与数据库保留用于学习和对照。知识库模块本轮完全不改，Python 暂未接入知识库检索。

```text
TypeScript 首页 / 研究任务 / 月报
                 │
                 ▼
Python FastAPI → LangGraph → LangChain → 百炼模型
                   ├─ 业务工具 → Java 业务 API
                   ├─ 市场适配器 → 行情 / 公告 / 申赎清单
                   └─ SQLite → 会话 / 运行 / 事件 / 图检查点

Java 业务事务 → Outbox → Python 事件判断 → Java 通知保存
```

## 能力与文件对应

| 能力 | Python 文件 | 当前说明 |
| --- | --- | --- |
| 会话、问答与历史 | `agent/api.py`、`repository.py` | 最近四轮历史用于理解问题，本轮事实重新取数 |
| 基金搜索、资料、净值、指标、比较 | `agent/tools.py` | 复用 Java 业务计算，参数先经 Pydantic 校验 |
| 自选、持仓、估值、收益与风险 | `agent/tools.py` | 仅读取当前登录用户的业务数据 |
| 关联 ETF 行情、历史趋势 | `agent/market.py` | 标记行情时间、过期状态，ETF 价格不冒充基金净值 |
| 持仓穿透、产业链、公告与权重 | `market.py`、`pcf.py`、`industries.py` | 优先申赎清单估算，失败退回定期披露；公告仅元数据 |
| 三角色通用研究 | `agent/graph.py` | 分析员、风险复核员、综合员按顺序运行，使用三个百炼模型 |
| 双基金并行研究 | `research.py` | 保留看多、看空并行和裁决图及 `/arena` 对照页 |
| 后台任务、进度、历史、取消 | `runtime.py`、`repository.py` | 网页断线不取消；取消后阻止迟到结果回写 |
| 月报 | `api.py`、`tools.py` | 服务端固定基金、组合或自选范围，以及指标日期 |
| 导出审批与下载 | `graph.py`、`api.py` | 真正使用 LangGraph 暂停和 SQLite 检查点恢复 |
| 回撤通知判断 | `agent/events.py` | 独立凭证、事件去重、阈值穿越、六小时冷却、静默标记 |
| 公开信息搜索与网页片段 | `agent/tavily.py` | 通过官方远端 MCP 初始化、发现和调用；配置 TAVILY_API_KEY 后开放 |
| 知识库 | 原 Java 模块 | 本轮不修改、不迁移 |

旧 Java 工具治理管理员页仍显示旧 MCP 注册表，不代表 Python 工具列表。旧运行不自动复制进 Python 历史，原记录和旧接口继续保留。

## 学习顺序

### 1. 工具：`agent/tools.py`

工具就是模型可以申请调用的 Python 函数。`calculate_fund_metrics` 不让模型口算收益，而是读取 Java 已计算好的指标。LangChain 的 `StructuredTool` 把函数名称、用途和参数格式交给模型。

`FundArguments` 是参数说明书：先检查基金代码和日期，再请求业务服务。登录令牌留在服务器内，模型看不到它，也不能自己指定用户身份或 URL。

月报的 `scope` 是服务端已确认的范围。Python 会覆盖工具中的日期、禁止范围外基金，不能仅靠提示词要求模型自觉遵守。

### 2. 流程：`agent/graph.py`（重点）

```text
START → analyst ──需要数据──→ tools
           ▲                    │
           └────────────────────┘
           │不再调用工具
           ▼
      risk_review → synthesis → verify → END
                                  │需要导出
                                  ▼
                            export_approval
                                  │确认后
                                  ▼
                                 END
```

- `StateGraph` 定义步骤，边决定下一步能去哪。
- `AgentState` 是整次研究共享的工作记录，保存消息、数据、证据编号、次数和回答。
- `bind_tools` 把工具交给分析员；模型返回 `tool_calls` 时进入工具节点，否则进入风险复核。
- `ToolMessage` 把工具结果送回模型，必须关联对应的 `tool_call_id`。
- 第二个模型复核草稿与数据限制，第三个模型整理最终回答。这两个节点是顺序执行；原双基金图的看多和看空才是并行。
- `verify` 检查 `[E01]` 编号是否来自本次工具记录。这不是自动事实核验，不能保证一句话的推理正确。
- `interrupt` 保存并暂停执行，`Command(resume=True)` 从检查点继续。审批后不重新调用前面的模型。

最多六轮分析和十次工具读取。单次数据最多 24,000 字符，本轮累计最多 60,000 字符。超过预算明确返回缺失，不无声截断半份数据。

### 3. 运行与保存：`runtime.py`、`repository.py`

后台任务与 HTTP 连接分开，所以网页关闭后研究仍可继续。数据库保存会话、运行、事件与结果，不保存登录令牌。

状态更新带条件：已取消的运行不会被迟到的模型回答改回成功。对外查询先检查当前用户是否拥有运行。

等待审批的运行可跨重启继续，使用本次请求的新令牌。普通执行过程被服务重启中断时标为失败，重新提交，不自动重跑昂贵模型请求。

### 4. 接口与页面：`agent/api.py`

FastAPI 把页面请求接到运行框架。SSE 按序号读取已保存事件，可用 `/runs/{id}/stream?after=序号` 重新订阅。

首页、研究任务、月报保留原布局，Agent 请求改到 Python。首页引用可以点开数据摘录。新的浏览器存储键避免把旧 Java 会话 ID 发给 Python。

## 启动

1. 在 `python-agent` 执行 `uv sync`，IDE 使用 `.venv/Scripts/python.exe`。
2. Python 执行 `uv run python main.py`，端口 8001；Java 业务端口 8080；前端执行 `npm run dev`。
3. 复用已有 `DASHSCOPE_API_KEY`。三个角色继续使用 `RESEARCH_BULL_MODEL`、`RESEARCH_BEAR_MODEL`、`RESEARCH_JUDGE_MODEL` 配置。
4. Python 自动生成被 Git 忽略的 `python-agent/data/agent-event.key`；Java 从工作区读取。分机器部署需配置双方相同的 `AGENT_EVENT_SECRET`。
5. Java Outbox 默认使用 Python；配置 `fund.agent.execution-backend=java` 可恢复旧事件分发器。Python 不可用时事件十秒后重试，业务写入不回滚。
6. 当前运行时采用一个 Uvicorn 进程，不使用 `--workers`。生产环境需配置网关，开发 Vite 代理不是生产网关。

## 实际边界

- ETF 申赎清单估算是股票篮子代理，不是基金会计持仓；清单日期无法确认时明确留空。
- 产业链是公开标签与主营业务关键词的初筛；公告未读取全文，不能宣称已经全文核验或证明涨跌因果。
- 月报按当前组合基金范围读取历史指标，不重建历史月末持仓，不是精确历史月结账簿。
- 静默通知保存待处理标记，保持旧语义；没有新增到点自动补发调度器。
- 公开数据源不可用时说明缺失，不能替换成模型编造的数据。

## 验证

Python 测试覆盖真实 LangGraph、工具调用、三角色交接、用户隔离、取消、引用错误、月报范围、跨服务实例审批恢复与事件去重冷却。Java 适配测试覆盖独立凭证、决策保存和网络失败重试。前端类型检查与构建通过。

真实百炼三模型已完成一次工具研究，使用模拟 Java 自选数据。尚未用真实账户走完浏览器到 Java、Python 的完整链路；不能把模拟数据检查当成真实业务联调。
