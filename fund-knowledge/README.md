# fund-knowledge

该模块承载不依赖 Spring、数据库和搜索引擎的知识库核心：文档状态机、领域模型、中文结构化切块、Embedding/索引端口、RRF 混合检索和上下文预算。基础设施实现位于 `fund-infrastructure`。

默认知识库关闭。启用后可选择 `local` 内存索引进行学习和接口联调，或选择 `elasticsearch` 执行真实 BM25 + dense-vector kNN；RRF 始终由 Java 应用层确定性完成。
