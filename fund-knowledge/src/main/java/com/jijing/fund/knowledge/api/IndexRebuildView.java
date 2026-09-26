package com.jijing.fund.knowledge.api;

import java.time.Instant;
import java.util.Map;

/**
 * 一次索引重建的记录。状态是仓库写入的字符串，不是枚举。
 * 激活只接受 {@code READY_TO_ACTIVATE}，回滚只接受 {@code ACTIVE}。
 * 期望条数和写入条数不一致时，错误码由治理服务写成 {@code INDEX_COUNT_MISMATCH}，创建调用本身仍返回该记录。
 */
public record IndexRebuildView(
        String rebuildId,
        String sourceAlias,
        String previousIndex,
        String targetIndex,
        String embeddingVersion,
        String chunkingVersion,
        String status,
        Long expectedChunkCount,
        long indexedChunkCount,
        Map<String, Object> validationReport,
        String errorCode,
        Instant createdAt,
        Instant completedAt) {}
