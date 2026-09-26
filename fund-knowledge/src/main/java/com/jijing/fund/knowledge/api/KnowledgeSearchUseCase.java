package com.jijing.fund.knowledge.api;

/**
 * 面向调用方的知识检索入口。
 * 无命中时返回空切片列表，而不是异常。查询不合法时抛出 {@link IllegalArgumentException}。
 * 结果里的切片可以缺少来源 URI 或标题，本入口不另报「缺少引用」。
 */
public interface KnowledgeSearchUseCase {
    /**
     * 按查询、基金、资料种类、发布日期和条数上限检索。返回值已经过上下文预算裁剪。
     */
    KnowledgeSearchResult search(KnowledgeSearchQuery query);
}
