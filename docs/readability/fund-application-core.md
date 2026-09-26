# fund-application 核心可读性审计

范围仅限 `fund-application` 的应用根包、`auth`、`dto`、`exception`，以及只覆盖这些类型的测试。组合、自选、风险、研究及其他子包未改。

## 已做的整理

源码里的类、接口、记录、异常，以及手写的方法和构造器都补了中文 JavaDoc，说明职责和失败行为。记录类型没有手写访问器或紧凑构造器，组件含义写在类型注释上。

原先挤在一行里的分支、循环和赋值已拆开，公共方法名和参数名保持不变。指标查询里重复的“只保留确认或修正净值”收成私有方法，过滤条件与原来一致。同步校验仍然先判断基金和区间，只有这两项都通过才把日期放进去重集合。

## 新增的失败路径测试

新测试类都以 `ReadabilityGapTest` 结尾，没有改已有测试，也没有为了让测试通过而改生产行为。

| 测试类 | 覆盖的失败 |
| --- | --- |
| `AuthApplicationServiceReadabilityGapTest` | 未知用户、停用账户、错误口令、空刷新令牌、无法识别的刷新令牌、过期令牌并作废令牌族、停用后拒绝轮换、当前用户不存在、用户名冲突、用户名或口令不合法 |
| `FundQueryApplicationServiceReadabilityGapTest` | 非法基金代码、净值区间缺日期、档案在本地和上游都不存在、历史区间有样本但档案缺失 |
| `FundSyncApplicationServiceReadabilityGapTest` | 区间颠倒、日期缺失、代码非法、锁冲突且不解锁、上游没有档案时写失败审计并解锁 |
| `FundMetricsQueryApplicationServiceReadabilityGapTest` | 非法代码或区间、基金不存在、只有估算净值、无法识别的口径、累计净值不完整 |
| `FundComparisonApplicationServiceReadabilityGapTest` | 对比列表缺失、去重后不足两只或超过十只、没有重叠区间、口径混用 |
| `FundBatchSyncApplicationServiceReadabilityGapTest` | 单只锁冲突、基金不存在、非法区间都计入失败并继续 |
| `FundMetricSnapshotApplicationServiceReadabilityGapTest` | 单只缺失和非法区间计入失败，其余基金仍写入快照 |

已有用例仍然覆盖档案命中、区间颠倒、回源补档、陈旧净值尾部刷新、同步成功淘汰缓存，以及登出撤销刷新令牌。这些没有重复写。

## 仍存在的问题

这些问题是当前行为，本次没有改。

1. 基金代码文本为 `null` 时，解析只捕获 `IllegalArgumentException`。领域代码构造抛出的空指针会直接冒出，不会变成无效查询。
2. 用户名为 `null` 时，规范化直接返回空串，不抛认证异常。注册会在领域账户构造处抛出 `IllegalArgumentException`；登录则当成“用户名或口令无效”。格式错误走的是另一句认证异常。
3. 注册或登录命令本身为 `null`，以及登出、停用、读取当前用户时身份对象为 `null`，都在解引用时抛空指针，没有统一的未认证错误。
4. 登出和停用不检查账户是否仍存在，仓储调用照常发生。
5. 登录时，格式不合法的用户名会返回格式说明；未知用户、停用账户和错误口令则共用一句“用户名或口令无效”。失败语义不统一。
6. 净值历史先装载再检查档案是否存在。历史区间且本地已有样本时不会回源；空样本或近期窗口会先装载档案。基金不存在异常里，历史查询用调用方原文，档案查询用解析后的代码。
7. 同步的失败审计和业务写入在同一个事务方法里。外层事务回滚时，失败审计可能一起回滚。
8. 批量同步和指标快照用 `catch (RuntimeException)` 吞掉单只失败，只保留计数，原因丢失。受检异常不会被接住。
9. 页大小 100、新鲜度 48 小时、陈旧尾部 3 天、口令长度 6 到 128、用户名正则、审计说明 500 字、最长周期起点 `1900-01-01` 仍是字面量。
10. 对比会先按请求区间计算，再按重叠区间重算，同一基金调用两次指标计算。
11. 调整净值不完整时静默改用单位净值，只有结果里的口径能看出发生过降级。未指定口径且累计净值齐全时则改用累计净值。
12. 刷新令牌摘要是无盐 SHA-256。算法不可用时抛出非法状态，而不是认证异常。
13. 日涨跌按相邻样本而不是相邻交易日计算，第一条为空。
14. 净值校验失败抛出的是领域异常 `ExternalDataSourceException`，和其他应用异常不在同一层。
15. 缓存淘汰依赖 Spring 事务同步；没有活动事务时立即淘汰。两条路径都保留。
16. 命令和结果记录不校验组件，非法组合要到服务方法才失败。指标快照的截止日期为 `null` 时，在生成周期起点处空指针，不会变成无效查询。
17. 已有的 `FundQueryApplicationServiceTest`、`FundSyncApplicationServiceTest`、`AuthLogoutRevokesRefreshTokensTest` 仍是压缩写法。按“只新增测试类”未改。

## 测试

`mvn -pl fund-application -am test` 通过。依赖模块原先未安装，因此加上 `-am` 先构建 `fund-domain` 和 `fund-analytics`。`fund-application` 共 37 个测试，失败 0，错误 0，跳过 0。
