package com.jijing.fund.knowledge.api;

/**
 * 索引重建的应用入口：创建、查询、激活和回滚。
 * 条数不一致时创建仍返回记录而不是抛错；激活和回滚对状态字符串做精确匹配。
 */
public interface KnowledgeIndexGovernanceUseCase {
    /**
     * 发起一次重建并返回当前记录。
     */
    IndexRebuildView create();

    /**
     * 按重建标识读取记录。
     */
    IndexRebuildView get(String rebuildId);

    /**
     * 把已就绪的重建切换为生效索引。
     */
    IndexRebuildView activate(String rebuildId);

    /**
     * 把生效中的重建切回上一版索引。
     */
    IndexRebuildView rollback(String rebuildId);
}
