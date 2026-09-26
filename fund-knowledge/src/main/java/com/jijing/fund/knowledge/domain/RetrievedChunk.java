package com.jijing.fund.knowledge.domain;

import java.util.Set;

/**
 * 检索命中的一片，带分数、名次和命中通道。
 * 通道为 null 时收成空集。切片上的来源、标题和页码可以缺失，本记录不补引用、也不因此视为无效命中。
 * 名次小于等于 0 时，倒数排名融合会改用该路列表中的位置。
 */
public record RetrievedChunk(DocumentChunk chunk, double score, int rank, Set<String> channels) {
    /**
     * 复制通道集合。null 视为没有任何通道。
     */
    public RetrievedChunk {
        channels = channels == null ? Set.of() : Set.copyOf(channels);
    }
}
