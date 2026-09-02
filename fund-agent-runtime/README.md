# fund-agent-runtime

该模块承载 Spring AI 模型编排、只读基金工具、MySQL 会话记忆、证据校验、执行审计和确定性安全策略。复杂任务由持久化 Plan/DAG Runtime 调度，并通过 LangGraph4j 研究子图编排固定角色、条件路由、受限重试和 JDBC 检查点；普通查询继续使用成本更低的直接 Tool 或单 Agent 路径。

会话记忆采用两层预算：普通用户/助手消息保存在 `agent_message`，按近似 Token 预算裁剪，同时保留消息条数安全上限；成功工具结果会确定性序列化为短期 `agent_fact_card`，保存完整 `EvidenceReference`、结构化数据和过期时间。后续轮次只加载仍有效且落在独立 Fact Card Token 预算内的数据，并将对应证据注入当前执行 Trace，使跨轮事实复用仍能通过引用校验；`agent_fact_card_usage` 精确记录每个 Run 实际消费的卡片。该过程不使用 LLM 自由文本摘要。

默认配置：

```yaml
fund:
  agent:
    max-conversation-tokens: 8000
    max-conversation-messages: 20
    fact-card-token-budget: 3000
    fact-card-max-count: 12
    fact-card-default-ttl: 24h
```

实时行情事实卡固定 5 分钟过期，板块/事件/持仓类固定 1 小时，文档类 30 天，基金概况 7 天，其余工具使用 `fact-card-default-ttl`。

默认 `fund.agent.enabled=false` 且所有 Spring AI 模型类型为 `none`，无需 API Key 即可启动原有系统。启用后要求配置 OpenAI 兼容 Chat Model。SSE 已使用 Spring AI 流式模型响应产生增量 `answer.delta`，并处理完成、失败、超时和客户端取消审计。
