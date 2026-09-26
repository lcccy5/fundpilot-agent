package com.jijing.fund.knowledge.exception;

/**
 * 知识库收到的参数不合法。
 * 切块、检索和注册在本模块内仍抛出 {@link IllegalArgumentException}，不抛出本类型。
 * 本类型留给模块边界把那些参数错误包装后继续抛出。
 */
public class KnowledgeInvalidArgumentException extends RuntimeException {
    /**
     * @param message 参数错误说明
     */
    public KnowledgeInvalidArgumentException(String message) {
        super(message);
    }

    /**
     * @param message 参数错误说明
     * @param cause 模块内部原始的参数异常，通常是 {@link IllegalArgumentException}
     */
    public KnowledgeInvalidArgumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
