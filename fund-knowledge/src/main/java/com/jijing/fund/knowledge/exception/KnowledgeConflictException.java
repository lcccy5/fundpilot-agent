package com.jijing.fund.knowledge.exception;

/**
 * 知识库操作与当前状态冲突，例如重试一条并未失败的任务。
 * 本模块的服务类不抛出它，由仓库实现在拒绝写入时抛出。注册重复内容时返回重复标志，而不是抛出本异常。
 */
public class KnowledgeConflictException extends RuntimeException {
    /**
     * @param message 给调用方看的冲突原因，不要放入原文或密钥
     */
    public KnowledgeConflictException(String message) {
        super(message);
    }
}
