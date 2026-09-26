# Fund Agent API 可读性审计

本次只处理 `fund-agent-runtime` 中下列包的主代码，以及已经存在的对应测试包和评测测试：

- `agent/api`
- `agent/capability`
- `agent/exception`
- `agent/event`
- `agent/execution`
- `agent/port`
- `agent/report`
- `agent/runtime`
- `src/test/java/com/jijing/fund/agent/evals`
- 上述包下新增的 `*ReadabilityGapTest`

编排、规划、路由、多智能体、审批、工具、图、MCP、通知、核对等模块没有改动。生产代码的分支、返回值和公开名称保持原样。

## 文档与排版

每个类、接口、枚举、记录，以及源码里写出的方法和构造器，都补上了中文 JavaDoc。注释说明职责和失败时的行为，不再用方法名复述一句“执行某操作”。

原先挤在一行里的多语句、长条件和方法体已拆开。拆开的范围包括计划协调器、内存图存储、任务工作者、能力执行器、月报启动器和评测测试。控制流和字符串内容没有改。

## 新增失败路径测试

| 测试类 | 锁定的行为 |
| --- | --- |
| `AgentCoordinatorReadabilityGapTest` | 空命令、空消息、空白消息、空所有者在写入前拒绝；缺失运行上的查询、取消、审批和拒绝都会拒绝；能力抛错后运行变为失败；工具预算为 0 时不调用步骤；空观察按当前实现抛出空指针 |
| `InMemoryAgentDagRepositoryReadabilityGapTest` | 缺失运行、空所有者和尚未保存的计划被拒绝；空失败原因仍会把运行标失败；租约到期后再失败会抛出租约丢失且不改成失败 |
| `CapabilityExecutionReadabilityGapTest` | 空执行器、空白类型和未注册类型被拒绝；空上下文和空白产物地址被拒绝；非法 JSON 和缺少基金代码在查询指标前失败；研究任务未完成时核对失败 |
| `ReportJobReadabilityGapTest` | 缺失月报任务不能读版本或写产物；多智能体未启用时空所有者在提交命令时被拒绝；对账缺失运行时拒绝且版本保持 0 |
| `TransactionalOutboxReadabilityGapTest` | 业务动作失败时事件不入盒；事件为空时业务已经执行，随后失败且没有可发布记录；空领域事件被分发器记为未知架构死信 |

`evals` 没有运行提交、缺失运行或任务执行入口，因此没有新增 `ReadabilityGapTest`。已有的 `PersonalizationEvalV3Test` 只补充了中文说明并拆开了挤在一行的断言。

## 仍保留的可读性和行为问题

下面这些问题会影响阅读或排错，但改动会改变现有行为，或会碰到本次范围之外的模块，所以没有修。

1. `AgentFactCard.memoryCategory` 用工具名子串归类。多个关键字同时出现时按源码顺序命中，未知工具静默归入 `OTHER`，没有失败信号。
2. `DomainEvent`、`AgentRunView`、`AgentTaskView`、`AgentPlanView`、`Observation`、`AgentToolCallRecord` 等记录不校验空字段，非法快照会一直传到调用方。
3. `TransactionalOutbox` 不是真正的事务。`appendWithBusiness` 在事件或去重键为空时，业务动作已经执行，然后抛出空指针。`claimPending` 不使用消费者和当前时间，尝试次数从不增加，也没有把失败改成 `RETRY_WAIT` 或死信。`consumeIdempotent` 在事件不存在时仍返回 `true`。
4. `InMemoryReportJobStore.saveVersionedArtifact` 丢弃内容地址、摘要、证据、截止时间和模型版本，只把状态改成成功并增加计数。`next < 1` 的分支到不了。
5. `MonthlyReportLauncher.launch` 创建月报任务后不返回任务。多智能体策略开启时，空所有者会先在范围外的计划校验中失败，而不是走到提交命令。`reconcile` 对空任务、所有者不匹配、运行未成功或正文缺失都静默返回。
6. `InMemoryAgentDagRepository.createConversation` 不保存会话。`dependencyIndex` 写入后不再读取。`RunState` 的时间参数和 `TaskState` 的 `Long` 参数被忽略。`markWaitingApproval`、`consumeApproval`、`rejectApproval`、`recoverExpiredLeases` 在关联运行记录缺失时会空指针。事件载荷用字符串拼接，只转义了失败原因里的部分字符，规则名或产物地址含引号时会得到非法 JSON。
7. `AgentRuntimeRepository` 的默认方法静默丢弃路由、事实卡片和会话状态。调用方无法区分“实现没写”和“确实没有数据”。带所有者的 `conversationExists` 默认忽略所有者。
8. `AgentCoordinator.submit` 在路由通过并创建运行之后才调用规划器。规划器为空时运行可能已经留下。`approve` 每次都新建一个范围外的 `ApprovalService`。没有执行权限时抛出的是策略异常，不是参数异常。
9. `PlanTaskWorker` 在虚拟线程上执行，并用墙钟续租。执行失败只有在租约仍有效时才会记成任务失败。`AgentPlanWorkerLoop.tick` 不隔离单次失败，工作者抛错时本拍不再回收租约。
10. 能力执行器使用 `IllegalArgumentException` 和 `IllegalStateException`，而不是 `agent.exception` 中的类型。基金比较和基金指标各自复制了一份必填字段读取。下跌归因和催化剂研究各有一份几乎相同的追踪适配器：催化剂版本在错误码为空时会因为 `Map.of` 拒绝空值而失败，归因版本会把空错误码收成空串。
11. `ExecutionBudget` 对负数扣减会增加余额。`BoundedReactExecutor` 把空证据列表当成没有新证据，但观察对象本身为空时抛出空指针，而不是一个停止原因。
12. `DomainEventDispatcher` 对不认识的事件类型返回成功。载荷里的数字无法解析时静默使用兜底值。通知出口为空时可以构造，处理回撤事件时才失败。
13. `EvidenceReference.document` 不校验页码是否颠倒。`FundAgentEvent.of` 使用当前系统时间，同一输入不能复现。
14. `PersonalizationEvalV3Test.load` 在数据集缺失时由 `requireNonNull` 失败。所有权样本里未知的 `deny` 值不会断言，漏写类型时该行仍可能通过。

## 未改项

- 未修改编排、规划、路由、多智能体、审批、工具、图、MCP、通知、核对模块。
- 未为了让测试通过而改变生产行为。
- 未新增评测包的失败路径测试。
- 未打开拉取请求。
