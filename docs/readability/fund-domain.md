# fund-domain 可读性审计

范围：仅 `fund-domain` 模块（`src/main` 与 `src/test`）。本次改动不涉及其他模块和根 POM。

## 总览

- 主代码 50 个类型（类、接口、枚举、记录）全部补充了中文 JavaDoc；源码中实际书写的每个方法和构造器（包括记录的紧凑构造器、私有方法、接口方法）都有中文 JavaDoc，写明职责以及遇到 null、空白、非法值时抛出什么异常或返回什么。模块内没有 Lombok，因此不存在“无源码的生成成员”需要跳过。
- 原有 11 处英文 JavaDoc 已翻译并扩写为中文，不再保留英文原文；原有 2 处简短中文 JavaDoc（`FundCode`、`ExternalFundDataProvider`）已扩写。
- 可读性修复只做格式调整：把挤在一行的方法、构造器和校验拆成多行，补齐运算符和逗号两侧的空格，把很长的布尔条件按子条件分行，并给 `ImportBatchStatus`、`ImportRow`、`RiskLevel` 补上 `package` 声明后的空行。没有改名、没有改逻辑、没有调整条件求值顺序，也没有改动 import。
- 测试从 2 个类、4 个用例增加到 26 个类、84 个用例，全部通过。新增用例只覆盖失败路径和边界行为（null、空值、非法值、重复调用、防御性拷贝），没有为了让测试通过而修改生产代码。

## 构建与测试

- 环境：OpenJDK 21.0.10。机器上没有预装 Maven，也没有 `mvnw`，因此临时下载 Apache Maven 3.9.9 到 `/tmp`（未提交到仓库）。
- 命令：`mvn -s .mvn/settings-public.xml -pl fund-domain test`。`.mvn/settings-public.xml` 是仓库自带的公共仓库配置，用于绕开不可用的公司镜像。
- 结果：改动前 4 个用例通过；改动后 84 个用例通过，0 失败，0 错误，0 跳过。

## 逐类型说明

下表中“注释”一列概括 JavaDoc 写了什么；“新增测试”一列列出新测试类；“遗留问题”一列记录本次按要求未改动、但值得后续处理的问题。

### cache / event / exception / lock

| 类型 | 注释 | 新增测试 | 遗留问题 |
| --- | --- | --- | --- |
| `FundQueryCache` | 接口职责；5 个方法的命中/未命中返回值、覆盖写入语义、`evict` 可重复调用 | 无（纯接口，实现在 infrastructure） | 使用通配符导入 `model.*`、`java.util.*` |
| `DomainEventPublisher` | 事务性发件箱语义；`NOOP` 常量忽略所有入参；`append` 的 null 与重复去重键由实现决定 | `DomainEventPublisherTest`：`NOOP` 接受全 null 参数、重复调用不抛异常 | `append` 有 7 个连续 `String` 参数，调用处容易传错顺序，建议后续引入参数对象（会改公开 API，本次不做） |
| `ExternalDataSourceException` | 异常用途、错误码含义；两个构造器和 `errorCode()` 均说明不校验 null | `ExternalDataSourceExceptionTest`：错误码/消息/原因原样保留，null 错误码与原因可接受 | 已把单行构造器拆开，无其他问题 |
| `FundSyncLock` | 同步互斥语义；`tryLock` 不阻塞、`unlock` 在未持有时静默 | 无（纯接口） | 无 |

### identity

| 类型 | 注释 | 新增测试 | 遗留问题 |
| --- | --- | --- | --- |
| `AccessTokenIssuer` | 签发职责、失败时抛运行时异常而非返回 null | 无（纯接口） | 原先整个接口挤在一行，已拆开 |
| `AuthenticatedUser` | 记录职责；构造器 null 角色变空集合、含 null 元素抛 NPE；`hasRole(null)` 抛 NPE | `AuthenticatedUserTest`：null 角色、防御性拷贝、含 null 元素、`hasRole(null)` | `hasRole(null)` 抛 NullPointerException 而不是返回 false，调用方容易误判；`userId`、`sessionId` 不校验 |
| `PasswordHasher` | 哈希与校验职责；`matches` 遇到 null 应返回 false | 无（纯接口） | 原先挤在一行，已拆开 |
| `RefreshTokenRecord` | 令牌家族与轮换语义；`activeAt` 的过期边界（等于过期时间即失效）和 null 行为 | `RefreshTokenRecordTest`：已撤销、过期边界、未撤销时 null 抛 NPE、已撤销时短路不读过期时间 | 构造时完全不校验，`expiresAt` 为 null 的记录要到调用 `activeAt` 时才会失败 |
| `UserAccount` | 聚合职责、令牌版本含义；构造器的登录名校验与角色规范化 | `UserAccountTest`：null/空白登录名、角色规范化与拷贝、含 null 角色 | `userId`、`status` 可为 null，构造出的账户可能无法持久化 |
| `UserAccountRepository` | 11 个方法逐一说明返回空、重复键报错、撤销类方法可重复调用 | 无（纯接口） | 已补齐参数逗号后的空格 |
| `UserId` | UUID 约束；构造器区分“缺失”与“格式错误”两类 IAE；`random()` | `UserIdTest`：null/空串/空白、非 UUID、`random()` 互不相同且可回构 | 不做规范化：`UUID.fromString` 宽松接受 `"1-1-1-1-1"` 这类非标准写法，且只差大小写的两个 ID 不相等（已实测确认）。修复会改变行为，本次只记录 |
| `UserRole` | 按名称持久化并写入令牌，不能随意改名 | 无（枚举） | 无 |
| `UserStatus` | 只有 ACTIVE 可登录；DISABLED、LEGACY 的含义 | 无（枚举） | `LEGACY` 在全仓库没有读写方，含义只能从“非 ACTIVE 不能登录”推断 |

### model

| 类型 | 注释 | 新增测试 | 遗留问题 |
| --- | --- | --- | --- |
| `FundCode` | 6 位 ASCII 数字、不去空白不补零；null 抛 NPE、格式错误抛 IAE | 追加到 `FundCodeTest`（原测试专测此类型，不属于共享文件）：null、空串、长度不对、首尾空白与结尾换行、全角数字 | 正则带 `^...$`，与其他值对象的正则写法（配合 `matches()` 不带锚点）不一致 |
| `FundProfile` | 必填项与可选字段 | `FundProfileTest`：4 个必填字段为 null、空白名称、可选字段为 null | 允许 `dataSource` 为空字符串 |
| `NavPoint` | 按字段顺序校验、首个非法字段即失败；正数约束 | `NavPointTest`：单位净值 0/负数、累计/复权净值 0/负数、可选净值缺失、6 个必填字段为 null | `java.time.Instant` 以全限定名写在记录组件里，而同文件其他类型都用 import；累计净值和复权净值两行 `if ... throw` 约 140 字符 |
| `NavStatus` | 三种状态含义，以及指标计算只使用 CONFIRMED 与 CORRECTED | 无（枚举） | 无 |

### portfolio

| 类型 | 注释 | 新增测试 | 遗留问题 |
| --- | --- | --- | --- |
| `FundPosition` | 持仓快照职责、不做校验；`averageCostPerShare` 的零份额、舍入规则与 NPE 条件 | `FundPositionTest`：零份额（含 `0.00`）返回 0 且不读成本、8 位 HALF_UP、缺失份额或成本时抛 NPE | `java.math.RoundingMode` 用全限定名；记录本身不校验任何字段 |
| `FundTransaction` | 只追加流水、幂等键、冲正引用；构造器校验与金额缺省为 0；私有 `zero` | `FundTransactionTest`：缺失标识/类型/日期/幂等键、金额缺省为 0、份额/金额/费用为负 | 7 个条件共用一条 `"transaction is invalid"` 消息，无法定位是哪个字段；组合、用户、基金代码可为 null；确认日期早于交易日期不会被拒绝；`java.time.*` 通配符导入 |
| `ImportBatch` | 两阶段导入语义；导入行规范化与不校验的统计字段 | `ImportBatchTest`：null 行、防御性拷贝、含 null 元素 | 行数统计与实际行数不一致、负数行数都不会被拒绝 |
| `ImportBatchStatus` | 三个状态的生命周期含义 | 无（枚举） | 无 |
| `ImportRow` | 行号、原始内容、错误码为 null 表示有效 | 无（无逻辑的记录） | 无 |
| `PortfolioId` | 与 `UserId` 相同的 UUID 约束与 `random()` | `PortfolioIdTest`：null/空串/空白、非 UUID、`random()` | 与 `UserId` 同样不做规范化、宽松解析；两者代码完全重复 |
| `PortfolioRepository` | 16 个方法逐一说明用户隔离、乐观锁返回 false 的含义、空列表、重复键报错 | 无（纯接口） | `markImportCommitted`、`deleteImportBatch` 只按批次标识定位、不带所属用户，依赖调用方先校验归属；`java.util.*` 通配符导入 |
| `PortfolioStatus` | ACTIVE 可录入交易、ARCHIVED 只读 | 无（枚举） | 无 |
| `TransactionType` | 8 种交易类型、冲正不可再冲正 | 无（枚举） | 8 个常量写在一行，约 150 字符 |
| `UserPortfolio` | 展示名校验及不校验的字段 | `UserPortfolioTest`：null/空串/空白展示名 | 组合标识、所属用户、状态可为 null |

### provider / repository

| 类型 | 注释 | 新增测试 | 遗留问题 |
| --- | --- | --- | --- |
| `ExternalFundDataProvider` | 防腐层边界；失败时抛 `ExternalDataSourceException` 而非返回 null | 无（纯接口） | `model.*` 通配符导入 |
| `FundNavRepository` | 区间查询、最新净值、幂等 upsert 与空列表返回 0 | 无（纯接口） | 通配符导入 |
| `FundRepository` | 数据修订号用途；`save` 后视为启用；`incrementDataRevision` 非幂等、基金不存在时静默 | 无（纯接口） | 通配符导入，且 `java.util.*` 排在 `java.time.LocalDate` 之前，与其他文件顺序不一致 |
| `FundSyncAuditRepository` | 审计记录独立于业务事务；记录不存在时静默 | 无（纯接口） | `java.time.*` 通配符导入 |
| `TradingCalendarRepository` | 交易日计数；未知市场或无日历时返回 0 | 无（纯接口） | 返回 0 无法区分“没有交易日”和“没有日历数据”；目前应用层没有调用方 |

### research.model / research.provider

| 类型 | 注释 | 新增测试 | 遗留问题 |
| --- | --- | --- | --- |
| `DataLineage` | 血缘职责；过滤 null 后至少一个输入；推导说明可缺失 | `DataLineageTest`：null/空列表/全 null 输入、过滤 null、结果不可变 | 无 |
| `DataProvenance` | 来源说明职责；地址安全约束（协议、主机、用户信息、查询参数、片段）；版本与告警的规范化 | `DataProvenanceTest`：http/https 大小写、非 http 协议与相对地址、无主机、带用户信息、带片段、5 个必填字段为 null、版本规范化、告警去重去空 | 原 `ResearchProvenanceTest` 同时测试 `SecurityQuote` 和 `DataProvenance`，属于共享文件，因此新用例放在按类型拆分的新测试类中，原文件只补充了中文 JavaDoc |
| `DerivationMetadata` | 版本必填、计算时间必填、局限说明规范化 | `DerivationMetadataTest`：两个版本字段 null/空白、null 计算时间、局限说明规范化 | 版本字符串不去空白，而同包的 `DataProvenance.sourceVersion` 会去空白，行为不一致 |
| `ExchangeSecurityCode` | 交易所规范化、代码不去空白；`providerSymbol()` | `ExchangeSecurityCodeTest`：大小写与空白规范化、bj 与空前缀、代码长度/空白/字母、null | 交易所会去空白而代码不会，调用方容易踩坑 |
| `LinkedExchangeFund` | 只能作为参考代理、不能当官方净值 | `LinkedExchangeFundTest`：3 个引用字段为 null、空白展示名 | 不校验来源的 `dataKind`（`SecurityQuote` 会校验），是否应要求 `UNDERLYING_ETF_PROXY` 需业务确认 |
| `MarketDataKind` | 不能按提供方名称推断；8 个常量逐一说明 | 无（枚举） | 无 |
| `ProviderId` | 规范化规则与 2~63 位格式约束 | `ProviderIdTest`：大小写/空白规范化、null、6 种非法格式、63/64 位长度边界 | 无 |
| `QualityStatus` | 不是异常分类；4 个常量说明 | 无（枚举） | 无 |
| `SecurityQuote` | 不能冒充官方净值；按字段顺序校验 | `SecurityQuoteTest`：现价 0/负数、空白名称、4 个必填字段为 null、可选价格字段缺失 | 昨收、涨跌额、涨跌幅不与现价做一致性校验 |
| `SourcedValue` | 值与血缘必须同时存在 | `SourcedValueTest`：值或血缘为 null | 无 |
| `FundDiscoveryProvider` | 查找关联场内基金；失败抛 `ExternalDataSourceException` | 无（纯接口） | 无 |
| `MarketQuoteProvider` | 获取最新行情；失败抛 `ExternalDataSourceException` | 无（纯接口） | 无 |

### risk / watchlist

| 类型 | 注释 | 新增测试 | 遗留问题 |
| --- | --- | --- | --- |
| `RiskLevel` | 声明顺序即等级高低 | 无（枚举） | 无 |
| `RiskProfile` | 只存答案哈希、以最新一条为准；构造器只检查 null | `RiskProfileTest`：5 个必填字段分别为 null | 只检查 null，空白字符串、负分、null 时间都会被接受；5 个条件共用一条消息 |
| `RiskProfileRepository` | 只追加、按确认时间取最新 | 无（纯接口） | 无 |
| `WatchlistGroup` | 分组职责；标识与名称校验、条目规范化 | `WatchlistGroupTest`：null/空白标识与名称、null 条目、防御性拷贝、含 null 元素 | `ownerUserId` 可为 null，但仓储实现的 `saveGroup`、`updateGroup` 会直接调用 `ownerUserId().value()` |
| `WatchlistItem` | 标签规范化、不去重不去空白 | `WatchlistItemTest`：null 标签、防御性拷贝且保留重复、含 null 元素 | `itemId`、`fundCode` 可为 null |
| `WatchlistRepository` | 用户隔离与乐观锁；返回 false 的含义；`saveItem` 在分组不属于该用户时可能静默不写入、重复基金抛 IAE | 无（纯接口） | `saveItem` 没有返回值，调用方无法得知是否真的写入 |

## 测试文件

- 修改：`model/FundCodeTest`（追加 5 个用例，补充中文 JavaDoc；原有两个一行式用例保持原样，与该文件既有风格一致）、`research/model/ResearchProvenanceTest`（只补充中文 JavaDoc，未改动用例）。
- 新增 24 个测试类：`event/DomainEventPublisherTest`、`exception/ExternalDataSourceExceptionTest`、`identity/{AuthenticatedUser,RefreshTokenRecord,UserAccount,UserId}Test`、`model/{FundProfile,NavPoint}Test`、`portfolio/{FundPosition,FundTransaction,ImportBatch,PortfolioId,UserPortfolio}Test`、`research/model/{DataLineage,DataProvenance,DerivationMetadata,ExchangeSecurityCode,LinkedExchangeFund,ProviderId,SecurityQuote,SourcedValue}Test`、`risk/RiskProfileTest`、`watchlist/{WatchlistGroup,WatchlistItem}Test`。
- 风格：沿用现有测试的 JUnit 5 + AssertJ、包级可见测试类、`@Test void 英文方法名()`；每个测试类、测试方法和私有辅助方法都有中文 JavaDoc。

## 跨类型的遗留问题

以下问题需要改变行为或公开 API，超出本次“只做格式与注释”的范围，因此只记录不修复：

1. **null 的异常类型不统一**：`FundCode`、`FundProfile`、`NavPoint` 以及 research 包对 null 抛 `NullPointerException`；`UserId`、`PortfolioId`、`UserAccount`、`FundTransaction`、`RiskProfile`、`WatchlistGroup` 对 null 抛 `IllegalArgumentException`。上层若按异常类型映射错误码，需要同时处理两种。
2. **错误消息过于笼统**：`"transaction is invalid"`、`"risk profile is invalid"`、`"watchlist group is invalid"` 都不指明具体字段。
3. **单行 `if (...) throw` 不加花括号**：`NavPoint`、`FundProfile`、`DataLineage`、`DerivationMetadata`、`ExchangeSecurityCode`、`LinkedExchangeFund`、`SecurityQuote` 使用这种写法。这些行本身不算挤在一起，也符合各自文件的既有风格，所以没有改；只有超长的几行（`NavPoint`、`DerivationMetadata`）影响阅读。
4. **通配符导入与全限定名**：见上表各行；改 import 不影响行为，但属于顺手修改，本次未做。
5. **枚举格式不一致**：`NavStatus`、`MarketDataKind`、`QualityStatus` 每行一个常量，其余枚举写在一行。
