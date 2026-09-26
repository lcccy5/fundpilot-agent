package com.jijing.fund.knowledge.exception;

/**
 * 知识库依赖暂时不可用。
 * 检索时重排失败不会抛出本类型，而是降级并在结果警告里写入 {@code RERANK_DEGRADED}。
 * 入库时的依赖失败写成可重试状态，也不抛出本类型。本类型由模块边界在无法继续对外服务时使用。
 */
public class KnowledgeUnavailableException extends RuntimeException {
    /**
     * @param message 对外说明
     * @param cause 底层依赖异常，可以为 null
     */
    public KnowledgeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
