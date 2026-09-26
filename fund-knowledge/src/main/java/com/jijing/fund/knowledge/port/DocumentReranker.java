package com.jijing.fund.knowledge.port;

import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.util.List;

/**
 * 对融合后的候选再排序。必须返回非 null 列表。
 * 候选为空时返回空列表，表示无命中。实现可以忽略查询。
 * 抛出 {@link RuntimeException} 时，检索改用融合结果并记录 {@code RERANK_DEGRADED}，不把这次检索判为失败。
 * 不要求检查引用；缺少来源的切片应照常返回。
 */
public interface DocumentReranker {
    /**
     * 返回不超过 {@code topK} 条的重排结果。{@code topK} 为 0 时返回空列表。
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK);
}
