package com.jijing.fund.knowledge.port;

import java.util.Map;

/**
 * 搜索引擎侧的索引重建、别名切换和回滚。
 * 期望条数与写入条数是否一致由治理服务比较，本端口只报告数字。
 */
public interface IndexGovernanceGateway {
    /**
     * 建好一个尚未切换别名的新索引，并返回条数与校验报告。
     */
    PreparedIndex rebuild(String rebuildId);

    /**
     * 把读写别名从旧索引切到新索引。
     */
    void activate(String readAlias, String writeAlias, String previousIndex, String targetIndex);

    /**
     * 把读写别名从当前索引切回上一版。
     */
    void rollback(String readAlias, String writeAlias, String currentIndex, String previousIndex);

    /**
     * 一次重建的引擎侧结果。{@code expectedCount} 与 {@code indexedCount} 不相等时，
     * 治理服务记录失败并仍然返回重建视图。校验报告可以为 null。
     */
    record PreparedIndex(
            String readAlias,
            String writeAlias,
            String previousIndex,
            String targetIndex,
            long expectedCount,
            long indexedCount,
            Map<String, Object> validation) {}
}
