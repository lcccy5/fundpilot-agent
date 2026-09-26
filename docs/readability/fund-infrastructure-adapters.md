# fund-infrastructure 适配器可读性审计

范围是 `fund-infrastructure` 里除 `infrastructure/persistence/**` 以外的适配器、缓存、锁、安全和任务存储，以及它们的测试。本次只补中文 JavaDoc、拆开挤在一行里的语句，并给已有失败分支补测试。生产行为没有改。

## 超时、空响应、重复提交、连接失败

| 类型 | 超时 | 空响应 | 重复提交 | 连接失败 |
| --- | --- | --- | --- | --- |
| `HttpExternalFundDataProvider` | 连接默认 2 秒，读默认 5 秒。读超时抛 `DATA_SOURCE_UNAVAILABLE`，文案是请求超时 | 档案体为 null、净值体或 `items` 为 null，抛 `DATA_QUALITY_ERROR`。404 档案返回空 | 无。注解上的重试只在 Spring 代理里生效 | 与读超时同一条 `ResourceAccessException` 分支，文案同样是请求超时 |
| `EastMoneyFundDataProvider` | 未设置连接或读超时 | 简称缺失或空白返回空。净值坏行跳过。经理页失败变成经理为 null | 无 | 任何运行时异常包成 `DATA_SOURCE_UNAVAILABLE`。非法 JSON 会先包一层“响应无效”，再被外层包成档案或净值加载失败 |
| `MockExternalFundDataProvider` | 无网络 | 未知代码的档案为空，净值为空列表 | 重复调用返回同一批内存样本 | 无 |
| `TencentMarketQuoteProvider` | 未设置 | 响应体为 null 返回空。引号内字段不够或现价、时间非法是契约错误 | 无 | 其他异常是 `MARKET_QUOTE_UNAVAILABLE` |
| `EastMoneyFundDiscoveryProvider` | 未设置 | 空正文或非法 JSON 抛 `FUND_DISCOVERY_UNAVAILABLE`。ETF 代码不符合 `[15]\\d{5}` 返回空 | 无 | 与空正文同一条 `Exception` 分支 |
| `AllowlistedHttpDocumentProvider` | 连接 3 秒，请求 20 秒，不跟随重定向 | 允许的类型即使正文为空也返回空字节。缺 Content-Type 或非 2xx 拒绝 | 无 | `IOException`（含连接超时和读超时）都是 `Document fetch failed`。中断会恢复中断标记 |
| `ElasticsearchHttpDocumentSearchIndex` | 连接缺省 2 秒，读缺省 10 秒 | 空分块列表不发请求。无法解析的批量正文抛 `Invalid Elasticsearch bulk response`。空字符串会被 Jackson 读成缺失节点，`errors` 默认为 false，当前不会拒绝。`errors:true` 整批拒绝 | 相同 chunk id 由集群 index 动作覆盖，Java 不看重复 | 超时和拒绝连接都是 `Elasticsearch request failed`。检索再包一层 `Elasticsearch search failed`。中断标记不恢复 |
| `ElasticsearchIndexGovernanceGateway` | 连接缺省 2 秒，读缺省 30 秒 | 别名 200 但正文无法解析是 `Invalid alias response`。reindex 的版本冲突只进报告 | 每次 rebuild 新建带时间戳的索引，没有去重 | 超时和拒绝连接都是 `Elasticsearch governance request failed` |
| `SpringAiDocumentEmbeddingAdapter` | 不设置，由模型客户端决定 | null 或长度不符抛 `EMBEDDING_DIMENSION_MISMATCH`。空文本列表返回空列表并记成功 | 相同文本会再次调用模型 | `embed` 把运行时异常记为 failed 后原样抛出。`embedQuery` 不增加失败计数 |
| `RedisFundQueryCache` / `RedisFundMetricsCache` | 不另设，随 Redis 客户端 | 值为 null 是未命中。坏 JSON 与读失败一样返回空 | 再次写入覆盖 | 读失败返回空。写失败和逐出失败只记警告。查历史版本失败时版本按 0 |
| `InMemoryFundQueryCache` | 净值 30 分钟后视为过期，时钟是 `Instant.now()` | 未写入返回空 | 同键覆盖 | 无 |
| `JdbcTransactionalOutbox` | 领取后租约 30 秒，没有 HTTP 超时 | 载荷序列化失败写成 `[]`。读回失败变成空 Map 或空列表 | `INSERT IGNORE`。追加不看影响行数。重复消费返回 false，且不把事件标成已发布 | 数据库异常直接抛出。工人如果分发抛错，事件停在发布中，等租约过期再领 |
| `JdbcNotificationStore` | 无 | 无单独空载荷分支 | 相同指纹 `INSERT IGNORE`，返回 false | 数据库异常直接抛出 |
| `JdbcDocumentMetadataRepository` | 无 | 警告序列化失败写成 `[]` | 相同来源、哈希，以及外部编号或 URI 匹配时返回 `duplicate=true`，不再插入 | 数据库异常直接抛出 |
| `JdbcGraphCheckpointStore` | 无 | 状态序列化或读回失败抛出，不当成可恢复 | 唯一键冲突转成 `duplicate graph checkpoint sequence`，不覆盖 | 数据库异常直接抛出 |
| `LocalRawDocumentStore` | 无 | 无 | 同一哈希已存在则保留第一次写入。并发撞上 `FileAlreadyExistsException` 也视为已写入 | 路径逃出根目录抛 `SecurityException` |
| `RedissonFundSyncLock` | 等待 0，租约 60 秒。中断返回 false 并恢复中断标记 | 无 | 拿不到返回 false | Redisson 连接异常不捕获 |
| `XlsxSpreadsheetTableReader` | 无 | null 或空字节抛“导入文件为空”。没有表头返回空列表。公式单元格读成空串 | 无 | 坏文件抛“无法解析”。解压比 0.01 是进程级静态值 |

## 测试

`mvn -pl fund-infrastructure test`：48 个测试，0 失败，1 个跳过。跳过的是 `RealElasticsearchIntegrationTest`，需要环境变量 `RUN_ELASTICSEARCH_INTEGRATION_TESTS=true`。同仓库的兄弟模块当时还没装进本地仓库，先执行了一次 `install -DskipTests` 才能让这条命令解析依赖。

新增类都以 `ReadabilityGapTest` 结尾，只覆盖上面已经存在、而原测试没有打到的分支：

- `HttpExternalFundDataProviderReadabilityGapTest`：读超时、拒绝连接、null 档案、null 净值条目、空白名称
- `EastMoneyFundDataProviderReadabilityGapTest`：空白简称、空净值正文、拒绝连接
- `TencentMarketQuoteProviderReadabilityGapTest`：无正文、空字段、拒绝连接
- `EastMoneyFundDiscoveryProviderReadabilityGapTest`：空正文、拒绝连接
- `AllowlistedHttpDocumentProviderReadabilityGapTest`：白名单上的不可达地址（连接超时或拒绝连接）
- `ElasticsearchHttpDocumentSearchIndexReadabilityGapTest`：读超时、拒绝连接、无法解析的批量正文
- `ElasticsearchIndexGovernanceGatewayReadabilityGapTest`：读超时、拒绝连接、空别名正文
- `SpringAiDocumentEmbeddingAdapterReadabilityGapTest`：模型抛错、null 向量
- `RedisFundQueryCacheReadabilityGapTest`、`RedisFundMetricsCacheReadabilityGapTest`：读失败、空值、坏 JSON、写失败
- `JdbcNotificationStoreReadabilityGapTest`：插入 0 行与 1 行
- `JdbcTransactionalOutboxReadabilityGapTest`：重复消费、无法序列化的载荷、`INSERT IGNORE`
- `JdbcGraphCheckpointStoreReadabilityGapTest`：重复序列
- `JdbcDocumentMetadataRepositoryReadabilityGapTest`：重复登记
- `LocalRawDocumentStoreReadabilityGapTest`：同一哈希第二次写入
- `XlsxSpreadsheetTableReaderReadabilityGapTest`：空文件

JDBC 重复提交依赖 MySQL 方言（`INSERT IGNORE`、`ON DUPLICATE KEY`）。没有嵌入式库能忠实执行这些语句，所以对应测试用 `JdbcTemplate` 桩验证 Java 分支，不假装跑过真实约束。

## 未改行为、因而没测或测不到的点

- 天天基金、腾讯行情、基金发现都没有超时配置。给它们补超时会改变挂起行为，所以没有做。
- 白名单文档拉取禁止回环和站点本地地址，MockWebServer 起不来。空正文、非 2xx 和 20 秒读超时没有 HTTP 桩。不可达地址的测试覆盖的是同一条 `IOException` 分支，连接超时是 3 秒。
- `InMemoryFundQueryCache` 的 30 分钟过期用 `Instant.now()`，不注入时钟就无法在单元测试里推进时间。
- `LocalRawDocumentStore` 里 `FileAlreadyExistsException` 只在存在检查和 `CREATE_NEW` 之间的竞态出现，单线程第二次写入走的是“文件已存在”短路。
- Elasticsearch 批量响应若是空字符串，Jackson 读成缺失节点，不会走进“响应无效”分支，调用方会以为写入成功。
- `EastMoneyFundDataProvider.first` 没有调用方。
- `fund.knowledge.elasticsearch-ca-certificate` 绑了配置，HTTP 客户端没有使用它。
- Elasticsearch 请求失败时不恢复中断标记。
- 发件箱工人不捕获分发异常，租约内不会自动改回待发布。
- 报告调度写死阈值 `0.80`、`0.81`、`1.1`，并用本机时钟而不是数据库时钟。
- Redis 历史键在版本读取失败时落到版本 0，可能读到另一代缓存。
- 标准 HTTP 供应商把拒绝连接和读超时写成同一句“请求超时”。
- 记录类型的访问器是编译器生成的，行为写在类型注释上，没有逐个再声明一遍。
- `infrastructure/persistence/**` 按要求未改。
