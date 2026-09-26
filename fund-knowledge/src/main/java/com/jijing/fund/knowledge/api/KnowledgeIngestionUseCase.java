package com.jijing.fund.knowledge.api;

/**
 * 文档注册入口。实现只受理原文和元数据，不在这次调用里解析或建索引。
 * 空正文和超过 50MB 的正文会被拒绝。是否为重复入库由持久化实现决定，并通过结果里的重复标志返回。
 */
public interface KnowledgeIngestionUseCase {
    /**
     * 注册一篇文档。校验失败时抛出 {@link IllegalArgumentException}，且不会留下新的原始字节。
     */
    DocumentRegistrationResult ingest(RegisterDocumentCommand command);
}
