package com.jijing.fund.knowledge.exception;

/**
 * 文档、版本、任务或重建记录不存在。
 * 检索无命中不是本异常，无命中返回空切片列表。本模块的服务类不抛出它，由仓库在按标识读取失败时抛出。
 */
public class KnowledgeNotFoundException extends RuntimeException {
    /**
     * @param message 说明哪一类记录没有找到，不要包含文档原文
     */
    public KnowledgeNotFoundException(String message) {
        super(message);
    }
}
