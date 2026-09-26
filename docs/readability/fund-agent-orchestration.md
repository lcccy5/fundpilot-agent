# 基金 Agent 编排可读性审计

本次只整理 `fund-agent-runtime` 中计划、路由、审批、多代理和编排的实现与对应测试，并补上失败路径说明。生产行为、公开名称和异常文案都没有改。

## 范围

- `agent/orchestration`
- `agent/planning`
- `agent/routing`
- `agent/multiagent`
- `agent/approval`
- 上述包的测试

未改 `api`、`capability`、`exception`、`event`、`execution`、`port`、`report`、`runtime`、`tool`、`graph`、`mcp`、`notification`、`verification`、`evals` 以及其他模块。

## 失败时发生什么

- **计划失败**：`PlanValidator` 在目标为空、任务为空、预算越界、所有者缺失、未知任务类型、依赖缺失或成环时抛出 `PlanValidationException`，整份计划作废。`ReplanPolicy` 在次数用尽时返回 false，不再允许新草稿。监督者试图追加计划外任务时同样抛出该异常。
- **路由失败**：没有执行权限时抛出 `AgentPolicyViolationException`，不产生决策。语义顾问超时、抛错、返回空正文或没有目标时失败开放，确定性路由器落到有界 ReAct 的已知规则，不发明未知模式。强制规则（审批、副作用、报告、自适应研究）在顾问之前生效。
- **审批拒绝**：`ApprovalService.isValid` 在已使用、过期时间为空、当前时间为空、当前时间晚于过期时间，或参数摘要不一致时返回 false。过期时间与当前时间相等仍视为有效。调用方必须停在导出、发布或通知之前。
- **对等代理失败**：多代理只在计划执行且调用方要求时启动。所有者空白会使分派在校验阶段失败，不返回部分绑定。数据研究员无权时先改派组合分析师，其他角色无权则抛出计划异常。产物缺少元数据、模式不是 v1、没有声明、缺少证据，或撰写者产出研究产物时，契约直接拒绝。工具调用失败只记 FAILED 审计，不把失败观察写成证据。

## 新增测试

类名均以 `ReadabilityGapTest` 结尾，只断言现有失败行为：

- `ApprovalServiceReadabilityGapTest`：过期、缺少过期时间、缺少当前时间。
- `PlanValidatorReadabilityGapTest`：空计划、空白目标、空白所有者，以及重规划额度用尽。
- `ExecutionModeRouterReadabilityGapTest`：空语义建议、没有目标的建议、空白原文，以及预检丢弃无目标响应。
- `MultiAgentSupervisorReadabilityGapTest`：权限不足、空白所有者、坏产物、未知能力。
- `AgentExecutionTraceReadabilityGapTest`：工具失败审计，以及受检异常被包装后冒出。

## 遗留的可读性问题

这些地方仍然难读，但继续拆会碰到控制流或对外契约，所以保留：

1. `SpringAiFundAgentService` 的同步和流式入口仍然很长。拒绝码在 `chat`、`failStream` 和 `streamErrorCode` 里各写一遍。
2. `FundAgentCitationPolicy.validateAndRepair` 仍在一个方法里完成清洗、分句、补引用和复查。用来判断“有没有缺失声明”的列表，之后没有再读取其中的标识。
3. `FundAgentConfiguration` 同时装配路由、能力、工具、通知和 MCP。`executionModeRouter` 的 `Environment` 参数没有被方法体使用。
4. `ExecutionModeRouter.features` 用中文关键词和固定数字估算阶段。澄清条件依赖“或”和“与”的优先级；括号已经写明，规则本身仍然密。
5. `SpringAiRouteAdvisor.advise` 捕获全部异常后返回空建议，其中包括中断，且不会恢复中断标志。这是失败开放的既定行为。
6. 监督者对数据研究员无权是静默改派，对其他角色无权是抛异常。两种失败看起来不对称。
7. 审批校验只返回布尔值，不能从结果区分已使用、过期、缺时间和摘要不符。
8. 没有单独的未知路由类型。顾问给不出建议时，结果仍是有界 ReAct 上的一条已知规则。未知任务类型要到计划校验才失败。
9. `PlanValidator` 在把 `maxTasks` 限制到 1 至 20 之后，仍判断任务数是否大于 20。在当前约束下，后一个判断不会单独触发。
10. 审批摘要和工具签名各自计算 SHA-256。事实卡序列化失败被吞掉，以免推翻已经成功的工具调用。
11. `MultiAgentAbEval` 在没有报告样本时把额外成本记为 1，而不是 0。
12. `ReplanPolicy` 没有被监督者调用。额度用尽只返回 false，本包里的调用方还要自己决定停不停。
13. 会话笔记、记忆选择、投影和路由器各自维护一套中文关键词，没有共用词表。
14. `AgentOpsPromptResolver` 的缓存没有容量上限，过期项要等到同一个键再次解析才会被跳过。
15. `MultiAgentAndOutboxTest` 仍同时断言发件箱、通知和 MCP。那些生产类型不在本次修改范围。
16. 英文提示词、路由版本号和异常消息保持原样，避免把可读性整理变成行为变化。
