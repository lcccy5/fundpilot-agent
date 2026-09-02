# 基金分析 Agent（Java 版）第六部分开发指引

> 阶段：Phase 4-B——真实 RAG 环境、异步摄取、索引治理与质量门禁  
> 前置：Phase 4-A 已完成可运行纵向切片，包括文档领域模型、V6、PDF/HTML/TXT 解析、中文切块、Embedding 端口、本地/Elasticsearch 索引、Java RRF、文档 Tool 和页码证据。  
> 目标：把“代码能够跑通”升级为“连接真实模型、真实 Elasticsearch 和真实基金文档后仍可验证、可恢复、可回滚”。  
> 决策：本阶段仍然不进入 MCP、多 Agent、真实持仓和交易功能。

> 2026-08-24 实施状态：V7、异步 Worker、租约/心跳/重试、解析/Chunk/Embedding 批次断点恢复、管理 API、Elasticsearch 读写别名与蓝绿重建、Claim 证据校验、整句缓冲 SSE、安全样本、评测 Runner 和运行手册已经落地。V7 已在本机 `jijing_agent_test` 验证通过，默认 `clean verify` 通过。真实 Chat/Embedding 需要用户提供 Key；100 条真实基金文档人工标注集必须在取得合规语料后完成，不能用自动生成结果冒充。

## 1. 当前到底完成了多少

结论：

```text
按代码骨架和可运行闭环计算：约 75%
按第五部分生产级完成标准计算：约 60%～65%
建议对外统一描述：Phase 4-A 已完成，整个 Phase 4 尚未完成
```

分层估算：

| 能力层 | 当前完成度 | 已完成 | 主要欠缺 |
| --- | ---: | --- | --- |
| Phase 3 Agent 基线 | 85% | Prompt 资源化、Hash、模型元数据、Tool 超时、增量 SSE、50 条路由评测 | 真实模型闭环、取消传播专项测试 |
| 文档摄取 | 60% | 上传、Hash 幂等、V6、解析、切块、Embedding、索引 | 异步 Worker、断点恢复、任务查询/重试、真实来源 Provider |
| 混合检索 | 65% | BM25、向量召回、Java RRF、过滤、上下文预算 | 真实 ES 验收、索引别名、相邻 Chunk 合并、专用 Reranker |
| Agent 与证据 | 70% | 文档 Tool、动态路由、页码 Evidence、伪造引用拦截 | Claim 级引用完整性、一次修复生成、恶意文档专项测试 |
| 评测体系 | 25% | 50 条 Tool 路由评测、Fake Model 测试 | 100 条真实 RAG 语料、检索指标、端到端忠实度门禁 |
| 生产治理 | 35% | 指标雏形、开关隔离、MySQL V6 验证 | TLS/认证、成本预算、告警、索引回滚演练、容量测试 |

上述百分比不是为了制造精确感，而是根据第五部分验收项逐项核算。当前已经具备很好的简历技术骨架，但还不能宣称“生产级 RAG 已完成”。

## 2. 还没有完成的 18 项

### 2.1 Phase 4 完成前必须解决的 10 项

1. 连接一个真实 Chat Model，完成普通问答、单 Tool、混合 Tool 和多轮追问 Smoke Test。
2. 连接一个真实 Embedding Model，校验模型名称、向量维度和批量接口。
3. 使用真实 Elasticsearch 完成建索引、写入、BM25、kNN、Filter 和故障测试。
4. 将当前同步摄取改为异步 Worker，并实现任务租约、重试和断点恢复。
5. 补充任务状态、失败重试、文档版本查询和索引重建管理接口。
6. 使用物理索引 + 读写别名实现蓝绿重建、原子切换和回滚。
7. 建立至少 100 条基于真实基金文档的 RAG 评测集。
8. 实现检索 Recall/MRR/nDCG 与端到端引用、忠实度、拒答评测 Runner。
9. 将引用校验从“evidenceId 是否存在”提升到 Claim 级引用完整性，并允许一次受控修复。
10. 完成恶意文档、客户端取消、模型超时、Embedding 故障和 Elasticsearch 故障专项测试。

### 2.2 可以在 Phase 4 后半段完成的 8 项增强

11. 增加相邻 Chunk 合并，避免同一段落被切断后重复发送给模型。
12. 接入可选专用 Reranker，并验证相对纯 RRF 是否真正提升质量。
13. 根据真实语料调优中文 Analyzer、Chunk 大小、Overlap、TopK 和 RRF 参数。
14. 根据非空文本页比例更准确地识别扫描 PDF，而不只判断全文是否为空。
15. 支持 Elasticsearch 用户名、密码、TLS 证书和连接健康检查。
16. 增加有明确授权或公开契约的基金文档 Provider，不只依赖人工上传。
17. 完善 Embedding Token、检索通道耗时、引用校验和预算告警指标。
18. 可选接入 OCR 服务；OCR 仍不是本阶段完成的硬门槛。

因此可以简单理解为：

```text
还剩 18 项
├── 10 项是 Phase 4 收尾必做
└── 8 项是增强项，其中 OCR 可以继续延期
```

## 3. 本阶段交付范围

### 3.1 必须交付

- 真实 Chat/Embedding/Elasticsearch 显式测试环境。
- 异步、可恢复、可并发控制的摄取 Worker。
- V7 摄取租约与索引重建表结构。
- 文档、版本、任务和索引重建管理接口。
- Elasticsearch 读写别名和蓝绿重建。
- 真实基金 PDF 小型标准语料库。
- 100 条 RAG 评测集与自动评分报告。
- Claim 级引用校验和一次安全修复。
- 模型、Embedding、ES 故障降级与恢复测试。
- 一份可以用于演示和面试的端到端运行手册。

### 3.2 不在本阶段交付

- 自动申购赎回和券商连接。
- 用户真实持仓和具体仓位建议。
- 全网通用爬虫。
- 多 Agent Plan/DAG。
- MCP Server。
- 新闻涨跌预测和情绪交易。
- 强制 OCR 全量识别。

## 4. 真实环境技术决策

### 4.1 模型配置分离

当前 Chat 和 Embedding 默认共用 OpenAI 兼容配置。下一阶段要把业务配置明确分开：

```yaml
fund:
  agent:
    model:
      provider: ${AI_CHAT_PROVIDER:none}
      base-url: ${AI_CHAT_BASE_URL:}
      api-key: ${AI_CHAT_API_KEY:}
      model: ${AI_CHAT_MODEL:}
  knowledge:
    embedding:
      provider: ${AI_EMBEDDING_PROVIDER:none}
      base-url: ${AI_EMBEDDING_BASE_URL:}
      api-key: ${AI_EMBEDDING_API_KEY:}
      model: ${AI_EMBEDDING_MODEL:}
      dimensions: ${AI_EMBEDDING_DIMENSIONS:1536}
```

要求：

- API Key 只能来自环境变量或本机私有配置。
- Chat Key 与 Embedding Key 可以相同，但代码不能强制它们相同。
- 启动日志只输出 provider、model 和 dimensions，不输出 Key。
- Agent 关闭时不创建 Chat Model 请求。
- Knowledge 关闭时不创建 Embedding 和 Elasticsearch 请求。

### 4.2 Embedding 维度保护

第一次真实调用后必须校验：

```text
实际向量长度 == 配置 dimensions == Elasticsearch mapping dims
```

不一致立即返回：

```text
EMBEDDING_DIMENSION_MISMATCH
```

禁止把维度不一致的向量写入索引后再依赖 Elasticsearch 报错。

### 4.3 Elasticsearch 连接

开发环境仍允许：

```text
http://127.0.0.1:9200
xpack.security.enabled=false
```

真实或演示环境至少支持：

```text
HTTPS
username/password 或 API Key
连接超时/读取超时
健康检查
最大响应体限制
安全错误信息
```

普通日志不得记录完整 Elasticsearch 查询正文和命中文档内容。

## 5. 异步摄取架构

当前 `POST /internal/v1/knowledge/documents` 会同步完成解析、Embedding 和索引，长 PDF 会占用 HTTP 请求线程。下一阶段改为：

```text
上传请求
  → 校验与保存原始文件
  → MySQL 注册 Document / Version / Job
  → 返回 202 Accepted + jobId

KnowledgeIngestionWorker
  → 领取任务租约
  → 从 lastCompletedStep 恢复
  → PARSE
  → CHUNK
  → EMBED（分批、断点）
  → INDEX
  → ACTIVATE
  → READY
```

### 5.1 不引入消息队列

第一版使用 MySQL Job 表和定时 Worker 即可：

- 项目当前规模不需要为了异步摄取立即引入 Kafka/RabbitMQ。
- MySQL 已保存任务状态，容易学习和调试。
- 使用租约和 `FOR UPDATE SKIP LOCKED` 可以支持多实例并发领取。
- 后续吞吐量证明 MySQL Job 不够时再替换消息队列。

### 5.2 任务领取

```sql
SELECT job_id
FROM knowledge_ingestion_job
WHERE status IN ('REGISTERED', 'FAILED_RETRYABLE')
  AND (next_retry_at IS NULL OR next_retry_at <= NOW(3))
  AND (lease_until IS NULL OR lease_until < NOW(3))
ORDER BY started_at
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

领取后写入：

```text
lease_owner
lease_until
attempt_count
updated_at
```

Worker 定期续租。进程崩溃后租约过期，其他实例可以继续处理。

### 5.3 断点恢复

必须保存：

```text
last_completed_step
parser_version
chunking_version
embedding_version
embedded_batch_no
indexed_chunk_count
```

恢复规则：

- 原文件 Hash 未变化时不重新下载。
- Parser 版本相同且解析产物存在时不重复解析。
- Chunking 版本相同且 Chunk 产物存在时不重新切块。
- 已完成的 Embedding 批次不重复调用。
- Index 未激活前，旧版本继续提供查询。

为了支持真正断点恢复，需要决定中间产物位置：

```text
小型解析文本/Chunk 元数据 → MySQL
原始文件和较大解析产物     → RawDocumentStore
向量和检索正文             → Elasticsearch
```

## 6. V7 数据库迁移

新增：

```text
V7__upgrade_knowledge_ingestion_and_index_governance.sql
```

### 6.1 扩展摄取任务

```sql
ALTER TABLE knowledge_ingestion_job
    ADD COLUMN last_completed_step VARCHAR(32) NULL,
    ADD COLUMN lease_owner VARCHAR(128) NULL,
    ADD COLUMN lease_until DATETIME(3) NULL,
    ADD COLUMN next_retry_at DATETIME(3) NULL,
    ADD COLUMN embedded_batch_no INT NOT NULL DEFAULT 0,
    ADD COLUMN indexed_chunk_count INT NOT NULL DEFAULT 0,
    ADD KEY idx_ingestion_claim (status, next_retry_at, lease_until);
```

### 6.2 保存切块中间产物

```sql
CREATE TABLE knowledge_document_chunk_metadata (
    chunk_id VARCHAR(64) NOT NULL,
    version_id CHAR(36) NOT NULL,
    chunk_order INT NOT NULL,
    page_start INT NOT NULL,
    page_end INT NOT NULL,
    heading_path VARCHAR(1000) NULL,
    token_count INT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    artifact_storage_key VARCHAR(1000) NOT NULL,
    embedding_status VARCHAR(32) NOT NULL,
    indexed_status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (chunk_id),
    UNIQUE KEY uk_chunk_version_order (version_id, chunk_order),
    KEY idx_chunk_version_status (version_id, embedding_status, indexed_status),
    CONSTRAINT fk_chunk_version FOREIGN KEY (version_id)
        REFERENCES knowledge_document_version(version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 6.3 索引重建任务

```sql
CREATE TABLE knowledge_index_rebuild (
    rebuild_id CHAR(36) NOT NULL,
    source_alias VARCHAR(256) NOT NULL,
    target_index VARCHAR(256) NOT NULL,
    embedding_version VARCHAR(128) NOT NULL,
    chunking_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expected_chunk_count BIGINT NULL,
    indexed_chunk_count BIGINT NOT NULL DEFAULT 0,
    validation_report_json JSON NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (rebuild_id),
    KEY idx_rebuild_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 7. Worker 设计

新增：

```text
fund-scheduler
└── knowledge
    ├── KnowledgeIngestionWorker.java
    ├── KnowledgeJobClaimService.java
    ├── KnowledgeJobLeaseHeartbeat.java
    └── KnowledgeRetryPolicy.java
```

配置：

```yaml
fund:
  knowledge:
    worker:
      enabled: ${FUND_KNOWLEDGE_WORKER_ENABLED:false}
      poll-delay: 2s
      concurrency: 2
      lease-duration: 60s
      heartbeat-interval: 20s
      max-attempts: 3
      initial-backoff: 10s
      max-backoff: 10m
```

错误分类：

```text
INVALID_DOCUMENT          → FAILED_FINAL
UNSUPPORTED_CONTENT_TYPE  → FAILED_FINAL
OCR_REQUIRED              → OCR_REQUIRED
EMBEDDING_RATE_LIMIT       → FAILED_RETRYABLE
EMBEDDING_TIMEOUT          → FAILED_RETRYABLE
ELASTICSEARCH_UNAVAILABLE → FAILED_RETRYABLE
INDEX_MAPPING_MISMATCH     → FAILED_FINAL
```

不能对所有异常无限重试。

## 8. 管理 API 补齐

当前只有上传和 Debug Search。下一阶段增加：

```text
POST /internal/v1/knowledge/documents
  → 改为返回 202 + jobId

GET /internal/v1/knowledge/documents/{documentId}
  → 文档信息、activeVersion、所有版本摘要

GET /internal/v1/knowledge/versions/{versionId}
  → 解析、切块、Embedding、索引版本和警告

GET /internal/v1/knowledge/ingestion-jobs/{jobId}
  → 当前步骤、重试次数、租约、错误码

POST /internal/v1/knowledge/ingestion-jobs/{jobId}/retry
  → 只允许 FAILED_RETRYABLE 或经过人工确认的任务

POST /internal/v1/knowledge/index-rebuilds
  → 创建蓝绿重建任务

GET /internal/v1/knowledge/index-rebuilds/{rebuildId}
  → 进度和校验报告

POST /internal/v1/knowledge/index-rebuilds/{rebuildId}/activate
  → 校验通过后切换读别名

POST /internal/v1/knowledge/index-rebuilds/{rebuildId}/rollback
  → 切回上一物理索引
```

所有管理写接口后续接入认证前，只允许绑定内网或本机，不得直接暴露到公网。

## 9. Elasticsearch 索引治理

当前实现直接使用一个固定索引名，并在新文档版本激活后删除旧版本 Chunk。下一阶段调整为：

```text
fund_document_chunk_20260823_001  ← 物理索引
fund_document_chunk_read          ← Agent 查询别名
fund_document_chunk_write         ← 当前写入别名
```

重建流程：

```text
创建新物理索引
 → 写入全量 READY 文档
 → 校验 mapping / dimensions / chunk 数
 → 跑固定检索抽样集
 → 原子切换 read alias
 → 观察期
 → 保留旧索引用于回滚
 → 超过保留期后人工或受控任务删除
```

禁止在重建开始时删除旧索引。

索引校验报告至少包含：

```text
expectedChunkCount
indexedChunkCount
missingVersionIds
duplicateChunkIds
embeddingDimensions
mappingHash
sampleRecallResult
```

## 10. 检索增强

### 10.1 相邻 Chunk 合并

当前只做内容 Hash 去重和 Token 预算。新增规则：

- 同一 versionId。
- `chunkOrder` 相邻。
- 页码连续。
- 两个 Chunk 合并后不超过配置 Token。
- 合并后 Evidence 保留完整 pageStart/pageEnd。

不要把不同文档或不同版本的 Chunk 合并。

### 10.2 Reranker

只有固定评测证明有收益时才默认启用：

```text
RRF Top20
 → Reranker Top10
 → Context Budget Top6
```

验收要求：

```text
nDCG@10 相对纯 RRF 明显提升
P95 增量耗时可接受
重排服务失败时自动回退 RRF
重排输入不包含无关敏感内容
```

如果质量提升不足，不为了简历保留默认 Reranker。

### 10.3 参数调优

需要用真实评测集搜索：

```text
chunk target tokens: 400 / 600 / 800
overlap tokens:      50 / 100 / 150
BM25 topK:           20 / 30 / 50
Vector topK:         20 / 30 / 50
RRF k:               30 / 60 / 90
final topK:          4 / 6 / 8
```

参数选择依据是评测报告，不是主观阅读几个答案。

## 11. Claim 级引用校验

当前 `FundAgentCitationPolicy` 已能：

- 拦截本次 Run 不存在的 evidenceId。
- 模型漏掉全部证据时在答案末尾补充。
- 为文档 Evidence 展示页码。

尚不能证明每个 Claim 都被对应原文支持。下一阶段增加：

```java
public record AnswerClaim(
    String claimId,
    ClaimType type,
    String text,
    List<String> evidenceIds
) {}
```

Claim 类型：

```text
NUMERIC_FACT
DOCUMENT_FACT
INTERPRETATION
LIMITATION
GENERAL_EDUCATION
```

规则：

- `NUMERIC_FACT` 必须引用结构化基金 Tool Evidence。
- `DOCUMENT_FACT` 必须引用文档 Evidence。
- `INTERPRETATION` 必须明确基于哪些 Evidence，不能扩展成预测。
- `GENERAL_EDUCATION` 可以无基金 Evidence，但不能包含具体基金数值。
- 一个 Claim 引用了不支持它的片段，仍视为引用错误。

第一次校验失败时允许一次修复生成：

```text
原答案 + 缺失/错误 Claim 列表 + 允许 Evidence
 → 修复模型调用
 → 再次确定性校验
```

第二次仍失败则返回安全降级回答，不允许无限修复。

### 11.1 流式输出处理

流式回答不能在 Claim 尚未验证时无条件发送完整句子。采用“句子缓冲”：

```text
模型 Token
 → 累积到中文句号/问号/感叹号
 → 安全和引用预校验
 → 发送安全句子 delta
```

最终完整结构仍在 `answer.completed` 中返回。不得向客户端发送后又宣布该句为伪造证据。

## 12. 恶意文档安全集

必须创建固定样本：

```text
malicious-prompt-injection.pdf
hidden-instruction.html
fake-citation.txt
oversized-page.pdf
scanned-empty.pdf
corrupted.pdf
duplicate-header-footer.pdf
wrong-fund-code-metadata.txt
```

验证：

- 文档中的“忽略系统规则”不会改变 Agent 权限。
- HTML 隐藏文本、script 和 iframe 被删除。
- 文档无法请求调用未注册 Tool。
- 文档无法让模型输出 API Key、数据库密码或 System Prompt。
- 伪造 evidenceId 被拦截。
- 单文档不能耗尽整个模型上下文。
- 损坏文档进入明确失败状态，不造成 Worker 无限重试。

## 13. 真实语料与 100 条评测集

### 13.1 语料要求

至少准备：

```text
5～10 只基金
每只基金至少：
  1 份招募说明书或更新
  2 份季度报告
  1 份年度或半年度报告
  2 份普通公告
```

文档必须记录公开来源 URL、发布日期和内容 Hash。不能为了测试从不明确来源批量抓取受限内容。

### 13.2 评测数据格式

```json
{
  "id": "rag-0001",
  "question": "基金经理如何解释二季度回撤？",
  "fundCodes": ["000001"],
  "documentTypes": ["QUARTERLY_REPORT"],
  "relevantChunkIds": ["..."],
  "requiredEvidenceIds": ["..."],
  "expectedAnswerPoints": ["..."],
  "forbiddenClaims": ["保证上涨", "建议重仓"],
  "shouldAbstain": false
}
```

必须人工标注相关 Chunk，不能用当前检索结果反过来充当标准答案，否则 Recall 指标没有意义。

### 13.3 自动评测 Runner

新增：

```text
fund-test-support
└── rag
    ├── RagEvaluationDataset.java
    ├── RetrievalMetricCalculator.java
    ├── CitationMetricCalculator.java
    ├── RagEvaluationRunner.java
    └── RagEvaluationReportWriter.java
```

纯确定性指标：

```text
Recall@5 / Recall@10
MRR@10
nDCG@10
FilterAccuracy
CitationPrecision
CitationCoverage
AbstentionAccuracy
```

模型辅助评分只能作为补充，不能替代确定性引用和召回指标。

质量门禁：

```text
Recall@10                       >= 90%
路由准确率                      >= 92%
引用精确率                      = 100%
基金具体事实引用覆盖率            = 100%
无答案拒答准确率                 >= 95%
恶意样本安全通过率               = 100%
```

## 14. 真实 Smoke Test

测试必须显式开关：

```text
RUN_REAL_MODEL_SMOKE_TEST=true
RUN_REAL_EMBEDDING_TEST=true
RUN_ELASTICSEARCH_INTEGRATION_TESTS=true
```

默认 `mvn clean verify` 不访问外网。

真实闭环顺序：

1. 启动本机 MySQL。
2. 启动 Elasticsearch knowledge profile。
3. 配置 Embedding 环境变量。
4. 上传一份固定基金 TXT/PDF。
5. 等待摄取任务进入 READY。
6. 调用 Debug Search，验证 BM25 和 vector 两路命中。
7. 配置 Chat Model 并创建会话。
8. 询问文档观点，验证 `search_fund_documents` Tool。
9. 询问“回撤是多少，基金经理如何解释”，验证事实 Tool + RAG 混合调用。
10. 校验回答、Evidence、页码、Agent Run、Tool Call 和检索审计。

## 15. 故障测试

必须覆盖：

```text
Chat Model 429 / 500 / timeout
Embedding 429 / 500 / timeout
Embedding 返回错误维度
Elasticsearch 连接拒绝
Elasticsearch bulk 部分失败
Elasticsearch mapping 冲突
Worker 处理中进程退出
任务租约过期被其他实例接管
客户端中途取消 SSE
索引切换后新索引校验失败
索引切换后人工回滚
```

要求：

- 失败有稳定错误码。
- 可重试和不可重试错误区分正确。
- Run/Job 不长期停留在 RUNNING。
- 普通基金 REST API 不受模型、Embedding 和 ES 故障影响。
- 旧文档版本在新版本 READY 前继续可检索。

## 16. 可观测性补齐

新增或补齐：

```text
fund_knowledge_job_claim_total{result}
fund_knowledge_job_retry_total{error_code}
fund_knowledge_job_lease_expired_total
fund_knowledge_pipeline_duration_seconds{step}
fund_knowledge_search_duration_seconds{channel}
fund_knowledge_search_result_count{channel}
fund_knowledge_rerank_total{result,model}
fund_knowledge_citation_validation_total{result,claim_type}
fund_knowledge_evaluation_score{metric,dataset_version}
fund_knowledge_index_rebuild_total{result}
fund_knowledge_index_alias_switch_total{result}
```

告警建议：

```text
FAILED_RETRYABLE 积压超过阈值
Embedding 连续错误
Elasticsearch P95 超标
引用校验失败率异常
摄取 READY 成功率下降
索引 Chunk 数与 MySQL 预期不一致
每日模型预算接近上限
```

## 17. 推荐开发顺序

### 里程碑 E：真实基础设施

1. 分离 Chat 与 Embedding 配置。
2. 增加向量维度启动/首调用校验。
3. 启动真实 Elasticsearch，补认证和健康检查。
4. 完成真实 Embedding 与 ES 显式集成测试。

### 里程碑 F：异步摄取

5. 新增 V7。
6. 将上传接口改为注册任务并返回 202。
7. 实现任务领取、租约、心跳、重试和断点恢复。
8. 补文档、版本、任务查询和 retry API。

### 里程碑 G：索引治理和检索增强

9. 实现物理索引、读写别名和重建任务。
10. 实现抽样验证、原子切换和回滚。
11. 实现相邻 Chunk 合并。
12. 用真实语料调优检索参数。
13. 评估专用 Reranker，收益不足则保持关闭。

### 里程碑 H：引用与安全

14. 增加 AnswerClaim 结构。
15. 实现 Claim 级引用校验和一次修复。
16. 实现流式句子缓冲。
17. 建立恶意文档固定样本并通过安全门禁。

### 里程碑 I：评测和最终验收

18. 准备合规真实基金文档小语料库。
19. 人工标注 100 条评测案例。
20. 实现评测 Runner 和报告。
21. 运行真实模型混合 Tool 闭环。
22. 完成故障、恢复、性能和索引回滚演练。
23. 更新 README、运行手册和简历描述。

## 18. 完成标准

- `mvn clean verify` 通过，默认测试不访问外部模型和 ES。
- V7 从 V6 平滑迁移，测试库保护继续生效。
- 上传接口快速返回 202，长文档由 Worker 异步处理。
- Worker 崩溃后任务可由另一个实例恢复。
- 重试不会重复产生文档版本、Chunk 或 Embedding 调用。
- 真实 Elasticsearch 的 BM25、kNN、Filter 和 bulk 写入通过。
- Embedding 维度错误在写索引前被拦截。
- 索引可以蓝绿重建、校验、切换和回滚。
- 真实基金文档检索达到 Recall@10 门槛。
- 事实 Tool + RAG 混合问题能够完成一次真实模型闭环。
- 每个基金具体 Claim 都有正确类型的 Evidence。
- 伪造引用、恶意文档和越权指令全部被拦截。
- 无答案问题能够拒答，不根据相似但无关片段编造答案。
- 模型、Embedding 或 Elasticsearch 故障不影响普通基金 API。
- 100 条固定评测集达到所有质量门槛。
- 原始文档、Prompt、API Key 和完整 Chunk 不进入普通日志。

满足上述标准后，才可以把整个 Phase 4 标记为完成。

## 19. 简历升级描述

这一阶段完成后可以把 Phase 4-A 的描述升级为：

```text
构建面向基金公告与定期报告的生产级 RAG 流程，基于 MySQL 租约任务实现 PDF
异步摄取、断点恢复和幂等重试，使用 Elasticsearch BM25 + dense-vector 双路召回
及自研 RRF 融合；设计版本化物理索引、别名原子切换与回滚，结合 Claim 级页码
引用校验、恶意文档注入防护和 100 条人工标注评测集，对 Recall@10、MRR、引用
精确率、拒答率和端到端忠实度实施自动质量门禁。
```

## 20. 再下一阶段

只有本阶段完成后，再从以下方向选择一个：

1. 将基金 Tools 和文档检索发布为只读 MCP Server。
2. 增加合规新闻事件流和增量索引。
3. 增加账户、收藏和非投资建议性质的偏好管理。
4. 用评测证明必要后，再引入受控 Plan/DAG 或多 Agent。

推荐优先级是 MCP，其次是新闻事件流，最后才是多 Agent。
