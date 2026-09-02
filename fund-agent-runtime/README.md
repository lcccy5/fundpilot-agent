# fund-agent-runtime

该模块已经承载 Phase 3 的模型编排、只读基金工具、MySQL 会话记忆、证据引用、执行审计、确定性安全策略和运行指标。模型接入与 Tool Calling 使用 Spring AI，业务执行边界由项目自行实现。详细设计见 [第四部分开发指引](../docs/04-Agent核心编排与基金工具化开发指引.md)。第一版采用受控单 Agent，不提前引入多 Agent Plan/DAG。

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
