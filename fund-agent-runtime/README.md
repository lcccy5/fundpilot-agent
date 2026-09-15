# fund-agent-runtime

该模块承载 Spring AI 模型编排、只读基金工具、MySQL 会话记忆、证据校验、执行审计和确定性安全策略。复杂任务由持久化 Plan/DAG Runtime 调度，并通过 LangGraph4j 研究子图编排固定角色、条件路由、受限重试和 JDBC 检查点；普通查询继续使用成本更低的直接 Tool 或单 Agent 路径。

会话记忆保留最近两个完整问答轮次，并同时受 3000 Token 上限约束。`agent_conversation_state` 以结构化小便签保存当前基金、已提及基金、时间区间和主题，不使用 LLM 自由文本摘要。成功工具结果按“会话 / 基金 / 分类”进入逻辑记忆文件夹；同类新结果替换当前指针，旧卡只保留审计。后续轮次按问题选择分类，每只基金组装一张 `FUND_MEMORY`，最多三只基金，并将对应 Evidence 注入当前执行 Trace。

默认配置：

```yaml
fund:
  agent:
    max-conversation-messages: 4
    max-conversation-tokens: 3000
    fact-card-token-budget: 1200
    fact-card-max-count: 3
    fact-card-default-ttl: 24h
```

实时行情事实卡固定 5 分钟过期，板块/事件/持仓类固定 1 小时，文档类 30 天，基金概况 7 天，其余工具使用 `fact-card-default-ttl`。

默认 `fund.agent.enabled=false` 且所有 Spring AI 模型类型为 `none`，无需 API Key 即可启动原有系统。启用后要求配置 OpenAI 兼容 Chat Model。SSE 已使用 Spring AI 流式模型响应产生增量 `answer.delta`，并处理完成、失败、超时和客户端取消审计。
