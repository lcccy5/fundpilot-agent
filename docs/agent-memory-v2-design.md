# FundPilot Agent Memory V2：选型、设计与评测

> 调研日期：2026-09-19。本文只把外部项目作为设计与评测参考，不复制其代码，也不把 benchmark 名称误当成可直接接入的生产组件。

## 1. 结论先行

FundPilot 不适合直接替换成 Mem0、Letta 或一个通用向量记忆库。基金研究中的净值、指标、行情、持仓和风险画像都有明确的数据所有者、时间口径与证据要求；如果把模型生成的摘要当成新的事实源，反而会削弱系统当前最有价值的可审计性。

推荐路线是：

1. 保留现有 `短期原文窗口 + Conversation Note + 带证据 Fact Card` 三层结构；
2. 借鉴 **STITCH/CAME-Bench**，把当前问题的目标、动作类型、基金实体和时间范围作为检索硬约束，先抑制错召回，再谈语义相似度；
3. 借鉴 **Memora**，区分“用于定位的抽象与 cue”和“保留细节的原始工具结果”，只把查询相关的投影视图送进 Prompt；
4. 借鉴 **MemoryAgentBench、LongMemEval 与 EvoMemBench** 建立递增式多轮评测，同时报告任务质量、错误记忆泄漏、工具调用和 Token 成本；
5. 等持久化 DAG 的跨任务复用需求稳定后，再增加经过验证的 episodic/procedural memory，不把失败轨迹或模型自由文本自动晋升为长期知识。

## 2. 图中项目分别能解决什么

| 项目 | 真正适合借鉴的部分 | 对 FundPilot 的适配度 | 不应直接照搬的原因 |
|---|---|---:|---|
| [LongMemEval V1](https://github.com/xiaowu0162/LongMemEval) | 单会话、多会话、时间推理、知识更新、拒答 | 中，适合回归集 | 主要是静态长历史 QA，不能覆盖工具执行与状态改变 |
| [LongMemEval V2](https://github.com/xiaowu0162/LongMemEval-V2) | Agent 轨迹到 memory/evidence/QA 的完整链路 | 高，适合第二阶段评测 | 发布较新，接入成本高于本项目当前的领域回归集 |
| [MemoryAgentBench](https://github.com/HUST-AI-HYZ/MemoryAgentBench) | 准确检索、测试时学习、长程理解、冲突消解 | 高 | 它是评测协议，不是生产记忆架构 |
| [MemoryArena](https://github.com/ZexueHe/MemoryArena) | 环境交互、多 session 状态、序列约束 | 中高，适合 DAG/多 Agent | 当前基金问答的主要瓶颈仍是事实检索，不是环境策略学习 |
| [CAME-Bench / STITCH](https://github.com/Seattleyrz/contextual-intent) | 用当前目标、动作类型、实体类型过滤语义相似但意图错误的历史 | **最高，适合当前线上检索** | 原实现依赖离线意图加工；本项目应先做确定性领域版，避免增加一次 LLM 延迟 |
| [EvoMemBench](https://github.com/DSAIL-Memory/EvoMemBench) | in/cross-episode × knowledge/execution；质量与效率并测 | **最高，适合评测框架** | 覆盖面很大，不需要一次性搬入全部任务环境 |
| [STATE-Bench](https://github.com/microsoft/STATE-Bench) | 真实多步工作流、最终状态、UX、成本、可复用 learning | 中，适合审批/报告工作流 | 其旅行、客服、购物领域与基金事实问答差异较大 |
| [Memora](https://github.com/microsoft/Memora) | value、primary abstraction、cue anchor 分离；混合检索与更新合并 | **高，适合表示层** | Python/ChromaDB 实现不值得替换现有 Java/MySQL 审计链路 |

因此不存在“选一个 benchmark 接进来”的答案。最合适的组合是：**STITCH 决定怎么找，Memora 决定怎么表示，EvoMemBench/MemoryAgentBench 决定怎么证明有效。**

## 3. 当前实现的基线与问题

### 已经做对的部分

- 最近完整问答同时受消息数和 Token 双预算约束，避免无限扩张；
- `AgentConversationState` 保存基金、时间段、主题，不依赖不可控的 LLM 自由摘要；
- Fact Card 只来自成功工具结果，保留 evidence、创建时间和 TTL；
- 以 `conversation × fund × category` 保存当前指针，历史卡仍可审计；
- Prompt 中使用的卡片记录到 `agent_fact_card_usage`，能追踪一次回答到底复用了什么；
- 已有真实模型 A/B，不能只靠“看起来更聪明”判断效果。

### V1 的关键缺口

1. **意图回退过宽**：只要当前问题没有命中类别关键词，就可能沿用上轮主题；普通寒暄也存在带入旧基金事实的风险。
2. **大值不可复用**：完整 NAV 序列或长文档命中会占满事实预算，整张卡可能被跳过，明明已有数据却再次调用工具。
3. **表示缺少边界**：Prompt 中只有业务 JSON，没有显式说明观察时间、有效期和证据 ID，模型需要从外围规则推断时效。
4. **相对时间不足**：`近三个月`、`今年` 等常见表达不能稳定写入结构化时间范围，容易造成跨轮期间错配。
5. **评测偏成功案例**：需把错基金、错主题、错期间、过期卡、冲突更新和无关问题拒绝纳入一等指标。

## 4. V2 目标架构

```text
用户问题
  └─ Intent Note：基金实体 / 数据类别 / 时间范围 / 是否指代上文
       └─ 硬过滤：owner + conversation + TTL + fund + category + period
            └─ 当前版本消歧：同 fund/category 只取最新有效卡
                 └─ Query-aware Projection：保留命中日期、边界点、Top 文档与必要字段
                      └─ Budget Packing：在 Token 预算内组装
                           └─ Evidence Packet：observedAt / validUntil / evidenceIds / value
                                └─ 模型回答 + 引用校验 + usage 审计
```

### 4.1 记忆类型和事实所有权

| 层 | 内容 | 权威来源 | 生命周期 |
|---|---|---|---|
| Working memory | 最近原始问答 | 对话消息 | 消息数 + Token 双阈值 |
| Intent/state memory | 当前基金、时间段、主题 | 用户显式表达和确定性解析 | 会话级，可被新表达覆盖 |
| Semantic fact memory | 基金资料、指标、行情、文档片段 | 成功工具结果 + Evidence | 类型化 TTL + 新版本覆盖当前指针 |
| Personal source of truth | 风险画像、自选、组合、持仓 | 业务数据库与个人工具 | 不从聊天摘要学习，查询时读取 |
| Episodic memory（后续） | 已完成任务、采用的证据与结果 | 验证通过的 Agent run | 跨任务，必须有 outcome 与版本 |
| Procedural memory（后续） | 某类任务的可靠执行策略 | 多次成功轨迹提炼并人工/评测验收 | 版本化发布，可回滚 |

金融场景中最重要的边界是：**模型回答不是事实源；用户风险偏好也不能仅凭一次聊天自动固化。**

### 4.2 写入策略

- 只有 `SUCCESS` 且存在可信 Evidence 的工具观察可以写入 Fact Card；
- 原始 `data_json` 保留用于审计，Prompt 投影不反写原值；
- 同基金同类别的新卡更新 current pointer，旧卡用于审计和冲突分析；
- 行情 5 分钟、市场信号/持仓 1 小时、基金资料 7 天、文档 30 天，继续使用类型化 TTL；
- 后续应增加 `sourceRevision/dataVersion` 失效条件，使数据库修订可立即淘汰旧卡，而不是只等待 TTL。

### 4.3 检索策略

检索分数不应由向量相似度单独决定。生产顺序应是：

1. owner / conversation 隔离；
2. 未过期；
3. 明确基金代码优先，指代词才允许使用 active fund；
4. 明确类别优先，只有真正的追问表达才允许沿用 active topic；
5. NAV/METRICS 必须与目标时间范围兼容；
6. 同一槽位采用最新有效观察，保留历史用于审计而非混入回答；
7. 未来数据量增大后，在硬过滤结果中再加入 BM25/embedding/cue-anchor 混合排序。

### 4.4 查询感知投影

- 大 NAV 序列：走势问题等距采样；期初/期末问题保留对应边界；具体日期问题保留命中日和范围边界；
- 文档检索：保留高排名少量片段并限制超长字符串；
- 每个注入段显式携带 `observedAt`、`validUntil`、`evidenceIds` 和 `value`；
- 投影只影响 Prompt，不损失数据库中的原始工具结果。

## 5. 评测协议

不能只报 Recall，也不能只报 Token。建议固定报告以下四组指标：

### 检索质量

- Recall@K：应该取到的卡是否取到；
- Precision@K / irrelevant leakage：是否混入错基金或错主题；
- conflict accuracy：知识更新后是否只采用当前版本；
- abstention accuracy：没有相关记忆时是否保持空检索；
- temporal compatibility：是否拒绝错期间指标。

### 回答质量

- 预期证据类型命中率；
- 引用可验证率；
- 知识更新题、时间推理题、多基金综合题通过率；
- stale fact answer rate，目标必须为 0。

### 效率

- Prompt / Completion / Total Token；
- memory payload Token；
- 工具调用与重复工具调用；
- 每轮记忆检索延迟、模型调用数和成本。

### Agent 任务价值

- DAG 任务完成率；
- 审批前后状态正确率；
- 跨任务经验复用后的成功率变化；
- 失败经验误迁移率。

当前新增的 `MemoryRetrievalQualityEvaluationTest` 是确定性第一层门禁，覆盖意图与实体过滤、多基金检索、期间冲突、无关问题拒绝、版本覆盖和大值预算。它不替代已有真实模型 A/B，也不能被表述为线上回答准确率。

## 6. 分阶段落地

### Phase 1：低风险、高收益（本次实现）

- 意图回退只对真正的上下文指代表达生效；
- 补充相对日期解析；
- 增加查询感知的大值投影；
- 注入明确的时效与证据边界；
- 增加检索质量 + 拒绝 + 成本联合门禁。

### Phase 2：数据版本驱动失效

- Fact Card 保存 `sourceRevision`、`contentHash`、`supersedesCardId`；
- 基金数据修订、组合交易追加、文档版本切换时主动失效；
- 增加冲突来源优先级和版本链查询。

### Phase 3：跨会话 verified episode

- 只保存已经完成且通过 verifier 的 run；
- episode 至少包含 goal、constraints、plan、evidence、outcome、failure class；
- 用户级检索必须做 owner/role/portfolio scope 隔离；
- 先用于规划参考，不直接作为基金事实引用。

### Phase 4：procedural learning

- 从多次成功 episode 中提炼策略候选；
- 离线回放超过基线后版本化发布；
- 线上保留 feature flag、审计和回滚；
- 用 EvoMemBench/STATE-Bench 风格同时验证成功率、稳定性与成本，避免只优化回答文风。

## 7. 可以对外准确表达的工程价值

建议描述为：

> 设计并实现面向基金研究 Agent 的分层记忆系统：使用 Token 受限短期窗口、结构化意图状态与带 Evidence/TTL 的事实卡；通过基金实体、任务意图和时间区间做确定性检索约束，并对大规模净值序列执行查询感知投影，在保留原始审计数据的同时降低 Prompt 载荷。建立覆盖冲突更新、错期间、无关问题拒绝、检索准确率、工具复用和 Token 成本的离线门禁，并以真实模型 A/B 验证回答质量与运行成本。

在没有重新跑真实模型 A/B 前，不应宣称本次改动提升了多少线上准确率或节省了多少总 Token；可以准确声明的是架构边界、覆盖的失败模式和确定性评测结果。
