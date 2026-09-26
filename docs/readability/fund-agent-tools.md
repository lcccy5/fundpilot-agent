# 基金 Agent 工具、图、MCP、通知与核对的可读性审计

审计范围只包括 `fund-agent-runtime` 中以下包的主代码和对应测试，以及本文：

- `agent/tool`
- `agent/graph`
- `agent/mcp`
- `agent/notification`
- `agent/verification`

未改 `api`、`capability`、`exception`、`event`、`execution`、`port`、`report`、`runtime`、`orchestration`、`planning`、`routing`、`multiagent`、`approval`、`evals` 或其他模块。生产行为、公开名称和工具描述字符串都没有为了测试而改动。

## 失败时各自怎么收场

工具适配器在缺少执行轨迹时直接抛出 `IllegalStateException`，不生成信封。参数不合法通常返回 `USER_CORRECTABLE`；数据源或用例抛出其他运行时异常时返回 `DATA_NOT_READY`，证据列表为空。指标不可用只进告警，不会被写成 0。

`FundProfileTool` 是例外：用例抛出的 `IllegalArgumentException` 也会落到 `FUND_PROFILE_FAILED`，而不是可修正错误。`PersonalFundTool` 成功信封版本是 `fund-tools-v1`，失败信封版本是 `fund-tools-v3`。

`FundRealtimeQuoteTool` 在行情对象为空时会先把本次调用记成成功，再按状态生成未就绪信封。若状态却是可用或过期，防御性异常随后又被收成 `REALTIME_QUOTE_FAILED`。正常的 `RealtimeFundQuoteResult` 构造器已经禁止“可用但没有行情”，这条分支只有不一致对象才能走到。

`FundCatalystResearchTool` 把执行预算耗尽原样抛出。基金或主题无法解析、没有日频申赎篮子时返回 `CATALYST_RESEARCH_UNSUPPORTED`。其他数据源失败返回固定文案 `CATALYST_DATA_UNAVAILABLE`。单只股票的行业或公告失败会被跳过，不会单独让整步失败。

`SectorOutlookTool` 对样本不足和主题无法解析返回 `SECTOR_UNSUPPORTED`，其他运行时失败返回固定的“板块行情数据源暂不可用”。行情客户端在字段里创建，当前不能注入故障。

进程内 `CatalystResearchGraph` 不保存检查点。研究或复核失败时抛出 `IllegalStateException("catalyst research graph failed")`。它不检查证据是否为空，缺少引用要由复核角色自己拒绝。可重试草稿最多再研究一次，然后仍会进入复核。

`ResumableCatalystResearchGraph` 和 `ResumableDeclineAttributionGraph` 用检查点恢复。状态阶段已经是 `COMPLETED` 时直接返回，不再调用研究员。复核前证据编号为空会抛出“verified evidence is required before review”，再被包成图失败。这两张图都没有调用 LangGraph4j 的 interrupt。

`DurableGraphCheckpointSaver` 在租约回调里写入快照并追加节点事件。租约回调拒绝写入时检查点不会落地。事件序列化失败时抛出 `IllegalStateException`，但内存存储上的快照可能已经写入，是否回滚取决于外层事务。同一检查点标识再次保存时替换，不新增序号。

`InMemoryGraphCheckpointStore` 拒绝同一运行和任务下的重复序号，替换不存在的检查点也会失败。查询按图版本过滤，版本不同视为没有可恢复快照。

MCP 外部正文永远不可信。模式摘要缺失或不一致时计划暂停。正文为空，或包含 `ignore previous`、`system prompt`、`evidence-id:forged` 时拒绝。`FundMcpServer` 拒绝参数中的 `userId`、未知工具、未登录的个人工具和被治理规则拒绝的参数，并先记录 `REJECTED` 再抛出。声称的证据编号不会变成可信引用。

通知在未穿越阈值时返回 `NO_CROSSING`，冷却期内返回 `COOLDOWN`，安静时段只占位并返回 `SCHEDULED_QUIET`。同一小时指纹已经占位后再次触发返回 `DEDUPED`。应用内通道拒绝空消息和包含“持仓明细”的摘要。通道抛出的异常不会被分发器吞掉。

`RunVerifier` 把未成功的研究任务记为未完成，并要求存在核对任务。已取消、等待审批和报告阶段任务不计入未完成。成功任务没有 `outputUri` 时证据列表为空，但核对仍可通过。`ReportWriter` 在报告缺失或未通过时拒绝写作；通过但没有证据编号时，正文会写明“本次任务未返回可追溯证据编号”。核对角色最多把计划退回一次。

## 已拆开的拥挤实现

原先把多条语句挤在同一行的工具方法、路由条件、个人数据工具、板块计算、催化研究的篮子解析和行情解析、图节点，以及 MCP、通知、核对里的同类单行，已按原有分支拆成多行。公开类名、方法名、常量名和返回码没有改。

## 新增的失败路径测试

新增类都以 `ReadabilityGapTest` 结尾，只覆盖原先没有断言的失败分支：

- `ToolFailureReadabilityGapTest`
- `CatalystResearchFailureReadabilityGapTest`
- `GraphFailureReadabilityGapTest`
- `McpFailureReadabilityGapTest`
- `NotificationFailureReadabilityGapTest`
- `VerificationFailureReadabilityGapTest`

## 遗留的可读性问题

1. `FundCatalystResearchTool.research` 仍然同时负责解析、持仓、行业、公告、影响和证据，方法过长。行业映射和公告检索在单只股票失败时吞掉异常，外层的 `INDUSTRY_MAPPING_UNAVAILABLE`、`VERIFIED_EVENT_SOURCE_UNAVAILABLE` 很难真正触发。
2. 工具描述仍写“无法获取时才使用最近公开披露持仓”，但 `fetchPortfolio` 在没有日频篮子时直接抛错。`fetchDisclosedPortfolio` 目前没有调用方。
3. `Input` 上的 `@Min`、`@Max` 以及实时行情、板块工具上的 `@Valid` 不会由工具方法自己执行。越界的 `topN` 可以构造成功。
4. 催化研究证据编号含随机 UUID，和 `ToolEvidenceFactory` 的稳定编号不是同一套规则。行业证据的 `industries` 参数没有写入证据。
5. `SectorOutlookTool` 的三个情景文案不使用传入的均线和现价。`resolve`、`live`、`history` 仍是长方法，客户端无法替换。
6. `FundToolRouter.toolsFor` 仍是一条关键词链。`message` 为 null 时抛出空指针，没有专门的参数错误信封。
7. `FundMcpServer` 对 `APPROVAL_REQUIRED` 的检查当前不可达，因为工具表里没有 `REPORT_EXPORT`。
8. 通知分发器的 `DEDUPED` 主要靠安静时段先占住同一小时指纹；同一次触发在冷却窗口内会先返回 `COOLDOWN`。规则键为 null 时由并发映射抛出空指针。
9. 核对通过不要求证据编号非空。缺少引用只在写报告时用一句中文说明，不会单独失败。
10. 图失败统一包一层 `IllegalStateException`，没有使用框架中断。检查点状态里的 null 值会在 `Map.copyOf` 处抛出空指针。SHA-256 不可用的分支在标准 JDK 上不可达。
11. `DurableGraphCheckpointSaver` 先写存储再序列化事件。序列化失败时，没有事务的存储实现会留下已经追加的快照。

## 测试结果

先安装同仓库的 `fund-domain`、`fund-analytics`、`fund-application`、`fund-knowledge` 后，执行 `mvn -pl fund-agent-runtime test`。结果是 135 个测试全部通过，没有失败、错误或跳过。图节点抛出的原始异常会被 LangGraph4j 包进 `GraphRunnerException`，测试断言的是最底层原因。

## 这次没有覆盖的失败路径

- `SectorOutlookTool` 的主题搜索失败、样本不足和行情解析失败需要真实 HTTP，或先把客户端改成可注入。按“不改生产行为”跳过。
- 催化研究里单票行业、公告和影响评估的外层失败码，在现有私有实现中会被内层 `catch` 吃掉，没有不改生产代码的稳定触发方式。
- MCP 的审批分支和 SHA-256 缺失分支对当前工具表和标准 JDK 不可达。
- 图的数字状态无法解析，要靠一次真实恢复出坏检查点；本次只覆盖了缺少证据、已完成短路、租约丢失、事件序列化失败和重复序号。
