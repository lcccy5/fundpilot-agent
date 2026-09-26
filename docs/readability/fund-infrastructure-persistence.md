# fund-infrastructure 持久化层可读性审计

范围只包括 `fund-infrastructure/src/main/java/com/jijing/fund/infrastructure/persistence` 下的实体、映射器和仓储，以及只覆盖这些类型的 `*ReadabilityGapTest`。SQL 文本、列映射和源码里的名字都保持原样。

## 存储与缺行

`fund` 保存基金主数据、数据源和修订号。按代码查不到行时，映射器返回 `null`，仓储返回空的 `Optional`。修订号查询在“没有这一行”和“列值为 SQL NULL”时都得到 `null`，仓储把这两种情况都折成 `0`。递增修订号时，基金不存在则更新 0 行，方法仍正常返回。`latestNavDate` 传入 `null` 会把已有行的最新净值日写成 SQL NULL，修订号照样加一。

`fund_nav` 按基金代码和净值日唯一。区间内没有净值时返回空列表；最新一条不存在时返回空的 `Optional`。代码、区间端点为 `null` 时，SQL 比较不成立，结果同样是空列表或 `null`。历史查询若映射器返回 `null` 列表，流式转换会抛出空指针；MyBatis 在正常查询里返回的是空列表。

`fund_sync_record` 按自增主键保存一次同步。`selectById` 没有命中时返回 `null`。成功和失败遇到缺行直接返回，不补插、不抛缺行异常。开始插入若没有回填主键，返回值拆箱会抛出空指针。

`fund_metric_snapshot` 没有查询方法。缺行和已存在行都走同一条 upsert。不可用指标和未知覆盖率写成 SQL NULL。数据版本为 `null` 或非数字时，在调用映射器之前抛出 `NumberFormatException`。

`trading_calendar` 只做开市日计数。没有交易日、市场或日期为 `null` 时都返回 `0`，调用方看不出是缺数据还是空参数。

自定义 `@Insert` / `@Update` 里的 `null` 会绑定成 SQL NULL。MyBatis-Plus 3.5.17 的 `GlobalConfig.DbConfig` 默认插入和更新策略是 `NOT_NULL`，所以 `fund_sync_record` 的 `insert` / `updateById` 会跳过 `null` 属性：可空列保持 NULL 或原值，不能靠把错误码设成 `null` 来清空已经写入的说明。本仓库没有改这个全局策略。

## 重复键

基金代码冲突时，upsert 覆盖名称、类型、管理人、经理、成立日、数据源和两个采集时间。新行的 `enabled` 固定写成 `1`。重复键更新不改启用标记、修订号和最新净值日，因此再次保存不会把已下线基金重新打开。

净值的代码加日期冲突时，只覆盖单位净值、累计净值、复权净值、状态和数据源。`source_updated_at` 与 `collected_at` 留在第一次插入的值上。空列表由仓储直接返回 `0`，不把空集合交给 `foreach`。同一批里的重复日期不会在内存中合并。

指标身份是 `(fund_code, period_code, nav_basis, data_revision, algorithm_version)`。冲突时覆盖区间、观测数、可空指标、无风险利率和计算时间，不改身份列。可空指标里的 `null` 会盖掉上一版的数字。最佳日收益、最差日收益、回撤区间和覆盖率状态没有列，写入时丢掉。

同步审计没有业务唯一键。只有显式主键冲突才会失败，仓储不把冲突改写成更新，异常原样抛出。

## 仍然难读的地方

- 注解里的 SQL 仍是长行。为了不改 SQL 文本，没有重排语句，也没有把已弃用的 `VALUES()` 改成别名写法。
- 访问器参数名仍是 `v`。名字被要求保持不变。
- `FundMetricSnapshotEntity` 用公有字段，其他实体用访问器。表上的自增主键、`created_at`、`updated_at` 没有映射，Java 侧看不到代理主键。
- `fund` 和 `fund_nav` 同样不映射 `created_at`、`updated_at`。实体上的 `enabled`、`dataRevision`、`latestNavDate` 不参与基金 upsert，读代码时容易以为 `setEnabled` 会落库。
- `getDataRevision` 把缺行和修订号 NULL 都变成 `0`。`incrementDataRevision` 不看更新行数，缺基金时像成功。
- 同步审计的成功和失败在缺行时静默返回。`REQUIRES_NEW` 仍会结束这个空事务。`syncedAt` 和 `startedAt` 各取一次 `Instant.now()`，两个时间可以不一致。
- 错误说明列最长 500，仓储不截断，超长文本要到数据库才失败。
- 净值状态为 `null` 或无法识别时，`NavStatus.valueOf` 在读路径上失败，不会先变成空结果。
- 启用基金列表或历史净值若拿到 `null` 列表会空指针。这是映射器违约时的行为，正常的 MyBatis 列表查询返回空列表。
- 直接调用净值 `upsertBatch` 且列表为空时，`foreach` 生成不出值元组，SQL 无效。只有仓储挡住了空列表。
- 交易日计数没有缺行对象，`0` 同时表示“没有开市日”和“参数是 null”。

## 测试

`mvn -pl fund-infrastructure test` 在本地仓库还没有兄弟模块的 SNAPSHOT 时无法解析 `fund-domain` 等依赖。先执行 `mvn -pl fund-infrastructure -am install -DskipTests` 装上反应器产物后，再执行 `mvn -pl fund-infrastructure test`。

结果：`Tests run: 34, Failures: 0, Errors: 0, Skipped: 1`。被跳过的是原有的 `RealElasticsearchIntegrationTest`，与本次持久化改动无关。新增测试类都是 `*ReadabilityGapTest`，用假映射器固定缺行、空主键和重复键异常上抛，不改变生产代码的返回值。

## 未执行的部分

- 没有 MySQL，`ON DUPLICATE KEY UPDATE` 的真实受影响行数（插入、更新、值未变化）没有跑过。重复键测试只证明仓储不捕获 `DuplicateKeyException`。
- `selectById(null)` 没有对真实会话执行。结论来自生成的 `WHERE id = #{id}`：SQL 里 `= NULL` 不命中，返回 `null`。
- `NOT_NULL` 会跳过空错误码这一行为，是对照 MyBatis-Plus 3.5.17 默认字段策略得出的，单测只看到仓储把属性设成 `null` 后仍调用 `updateById`。
- 交易日历没有写入方法，因此没有重复键失败路径。
- 映射器接口本身没有在数据库上执行。
