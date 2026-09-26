package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.util.List;

/**
 * 一次检索的返回。无命中时切片列表为空，检索号仍然存在。
 * 重排降级时警告里会出现 {@code RERANK_DEGRADED}；仅仅没有命中不会写入警告。
 * 切片缺少引用字段时也不会写入警告。
 */
public record KnowledgeSearchResult(String retrievalId, List<RetrievedChunk> chunks, List<String> warnings) {
    /**
     * 复制切片和警告。null 收成空列表，因此无命中和「未提供警告」都变成空列表而不是 null。
     */
    public KnowledgeSearchResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
