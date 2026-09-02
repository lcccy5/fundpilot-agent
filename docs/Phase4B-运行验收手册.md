# Phase 4-B 运行与验收手册

## 1. 已落地能力

- 上传只保存原始文件并登记 MySQL 任务，非重复请求返回 `202 Accepted`。
- Worker 使用 `FOR UPDATE SKIP LOCKED`、60 秒租约、20 秒心跳和受控并发领取任务。
- 解析结果、Chunk 和每个 Embedding 批次保存为原子替换的本地中间产物；进程退出后从最后成功步骤恢复。
- V7 保存任务租约、批次进度、Chunk 元数据和索引重建记录。
- Embedding 返回维度在进入索引前校验；批量写入检查 Elasticsearch 部分失败。
- Elasticsearch 支持 Basic/API Key、连接/请求超时、物理索引重建、计数与 Mapping 校验、读写别名原子切换和回滚。
- 检索上下文可合并同版本、相邻页码的 Chunk，Reranker 失败时保持 RRF 结果。
- Agent 具有 Claim 类型、证据类型校验、伪造引用拦截和一次受控补证据；SSE 只发送经过预校验的完整句子。
- 固定安全样本覆盖隐藏 HTML、伪造引用和错误基金元数据。
- 评测组件计算 Recall@10、MRR@10、nDCG@10、引用精确率/覆盖率、拒答准确率和安全通过率，并执行硬门禁。

## 2. 本地依赖

MySQL 仍使用本机安装，不在 Docker 中运行。Docker 只启动 Redis 和可选 Elasticsearch：

```powershell
docker compose up -d redis
docker compose --profile knowledge up -d elasticsearch
```

敏感配置只放环境变量：

```powershell
$env:MYSQL_PASSWORD='你的本机密码'
$env:FUND_KNOWLEDGE_ENABLED='true'
$env:FUND_KNOWLEDGE_WORKER_ENABLED='true'
$env:FUND_KNOWLEDGE_INDEX_TYPE='elasticsearch'
$env:AI_EMBEDDING_PROVIDER='openai'
$env:AI_EMBEDDING_API_KEY='你的 Embedding Key'
$env:AI_EMBEDDING_BASE_URL='兼容地址'
$env:AI_EMBEDDING_MODEL='模型名'
$env:AI_EMBEDDING_DIMENSIONS='1536'
```

Chat 与 Embedding 的 Key/Base URL 独立。兼容旧环境变量 `AI_API_KEY` 和 `AI_BASE_URL`，但新环境推荐使用 `AI_CHAT_*` 与 `AI_EMBEDDING_*`。

## 3. 异步摄取验收

1. 调用 `POST /internal/v1/knowledge/documents`，确认首次上传返回 HTTP 202 和 `jobId`。
2. 轮询 `GET /internal/v1/knowledge/ingestion-jobs/{jobId}`。
3. 观察 `lastCompletedStep`、`embeddedBatchNo`、`indexedChunkCount`，最终状态应为 `READY`。
4. 在 `EMBEDDING` 阶段停止应用，再启动；已经完成的 Embedding 批次不应再次请求。
5. 失败任务使用 `POST /internal/v1/knowledge/ingestion-jobs/{jobId}/retry`，非失败任务应被拒绝。

文档和版本查询：

```text
GET /internal/v1/knowledge/documents/{documentId}
GET /internal/v1/knowledge/versions/{versionId}
GET /internal/v1/knowledge/ingestion-jobs/{jobId}
```

## 4. 索引重建验收

仅在 `fund.knowledge.index-type=elasticsearch` 时开放：

```text
POST /internal/v1/knowledge/index-rebuilds
GET  /internal/v1/knowledge/index-rebuilds/{rebuildId}
POST /internal/v1/knowledge/index-rebuilds/{rebuildId}/activate
POST /internal/v1/knowledge/index-rebuilds/{rebuildId}/rollback
```

创建操作复制当前物理索引的 Mapping，执行 `_reindex`，比较源/目标文档数并记录 Mapping Hash、向量维度、版本冲突和失败项。只有 `READY_TO_ACTIVATE` 可切换；激活通过 `_aliases` 单请求原子切换读写别名。旧索引不会自动删除，用于人工回滚和观察期。

## 5. 自动测试

默认构建不访问外网：

```powershell
mvn -gs .mvn/settings-global-public.xml clean verify
```

显式真实验收开关：

```powershell
$env:RUN_REAL_MODEL_SMOKE_TEST='true'
$env:RUN_REAL_EMBEDDING_TEST='true'
$env:RUN_ELASTICSEARCH_INTEGRATION_TESTS='true'
```

没有模型 Key或未启动 Elasticsearch 时，这些测试保持跳过，不会用 Mock 冒充真实验收。

## 6. 质量门禁

评测 Runner 的默认硬门槛：

```text
Recall@10             >= 0.90
CitationPrecision      = 1.00
CitationCoverage       = 1.00
AbstentionAccuracy    >= 0.95
SafetyPassRate         = 1.00
```

真实 100 条数据必须由人工基于已经取得授权的基金文档标注。项目提供 Runner 和 Schema，但禁止把当前检索输出倒灌成标准答案。没有真实文档和人工标注报告时，不得声称真实质量门禁已经通过。

## 7. 安全边界

- 管理 API 只允许部署在内网或本机；当前版本不应直接暴露公网。
- 原始文档、完整 Chunk、模型 Key、数据库密码不得写普通日志。
- 文档正文是不可信资料，不能覆盖系统提示词或请求未注册工具。
- 真实环境推荐使用 HTTPS 和 Elasticsearch API Key；证书由 JVM TrustStore 或部署层注入。
- 项目只提供信息分析，不执行申购赎回，不给出确定性仓位建议。
