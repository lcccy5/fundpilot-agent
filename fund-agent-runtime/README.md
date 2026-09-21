# fund-agent-runtime

该模块承载 Spring AI 模型编排、只读基金工具、MySQL 会话记忆、证据校验、执行审计和确定性安全策略。复杂任务由持久化 Plan/DAG Runtime 调度，并通过 LangGraph4j 研究子图编排固定角色、条件路由、受限重试和 JDBC 检查点；普通查询继续使用成本更低的直接 Tool 或单 Agent 路径。

执行模式采用混合路由：权限、副作用、审批、报告和明确多阶段任务始终由确定性规则决策；未命中强规则的灰区请求由独立的阿里云百炼 `qwen3.8-flash` 小模型提取目标、所需能力、步骤依赖、跨源核验和迭代研究等结构化特征，不允许模型自行选择执行模式或提供自报置信度，最终决策权仍在 Java 路由器。路由模型使用独立 API Key 和 OpenAI 兼容地址，不会替换主对话模型；未配置 Key、调用超时或返回非法 JSON 时会回退到 `BOUNDED_REACT`。只有轻量执行真正尝试突破工具预算时，已登录请求才允许升级一次到持久化 DAG；重复调用和工具超时不会被误判为复杂任务升级。原运行和新路由均保留审计记录。

会话记忆保留最近两个完整问答轮次，并同时受 3000 Token 上限约束。`agent_conversation_state` 以结构化小便签保存当前基金、已提及基金、时间区间和主题，不使用 LLM 自由文本摘要。成功工具结果按“会话 / 基金 / 分类”进入逻辑记忆文件夹；同类新结果替换当前指针，旧卡只保留审计。后续轮次按问题选择分类，每只基金组装一张 `FUND_MEMORY`，最多三只基金，并将对应 Evidence 注入当前执行 Trace。

记忆检索采用意图约束而非无条件继承：只有明确的指代或追问表达才允许沿用上一轮主题，并对基金代码、数据类别、指标时间区间和 TTL 做过滤。同一基金/类别发生更新时只向模型提供最新有效观察。大规模净值序列与文档结果在读取时执行查询感知投影，原始工具 JSON 仍完整保留用于审计；Prompt 中的投影视图显式携带观察时间、有效期和 Evidence ID。设计依据、外部 benchmark 对比和后续演进方案见 [`docs/agent-memory-v2-design.md`](../docs/agent-memory-v2-design.md)。

默认配置：

```yaml
fund:
  agent:
    routing:
      semantic-enabled: true
      api-key: ${AI_ROUTER_API_KEY:}
      base-url: ${AI_ROUTER_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      model: ${AI_ROUTER_MODEL:qwen3.8-flash}
      semantic-timeout: 3s
    max-conversation-messages: 4
    max-conversation-tokens: 3000
    fact-card-token-budget: 1200
    fact-card-max-count: 3
    fact-card-default-ttl: 24h
```

实时行情事实卡固定 5 分钟过期，板块/事件/持仓类固定 1 小时，文档类 30 天，基金概况 7 天，其余工具使用 `fact-card-default-ttl`。

默认 `fund.agent.enabled=false` 且所有 Spring AI 模型类型为 `none`，无需 API Key 即可启动原有系统。启用后要求配置 OpenAI 兼容 Chat Model。SSE 已使用 Spring AI 流式模型响应产生增量 `answer.delta`，并处理完成、失败、超时和客户端取消审计。
