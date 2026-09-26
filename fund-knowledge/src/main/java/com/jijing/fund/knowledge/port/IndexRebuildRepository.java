package com.jijing.fund.knowledge.port;

import com.jijing.fund.knowledge.api.IndexRebuildView;
import java.time.Instant;
import java.util.Map;

/**
 * 索引重建记录的持久化端口。状态使用字符串，治理服务只识别
 * {@code READY_TO_ACTIVATE} 和 {@code ACTIVE} 两个精确值。
 */
public interface IndexRebuildRepository {
    /**
     * 新建一条重建记录。此时尚未写入就绪或失败。
     */
    void create(
            String id,
            String alias,
            String previous,
            String target,
            String embeddingVersion,
            String chunkingVersion,
            Instant now);

    /**
     * 把重建标成可以激活，并记下期望条数、实际条数和校验报告。
     */
    void ready(String id, long expected, long indexed, Map<String, Object> report, Instant now);

    /**
     * 在期望状态匹配时改到新状态。期望状态不匹配时的行为由实现决定。
     */
    void status(String id, String expectedStatus, String status, Instant now);

    /**
     * 记录重建失败。条数不一致时治理服务传入 {@code INDEX_COUNT_MISMATCH}，调用方仍会接着读取记录。
     */
    void fail(String id, String code, Map<String, Object> report, Instant now);

    /**
     * 按标识读取重建。不存在时的异常由实现决定。
     */
    IndexRebuildView find(String id);
}
