package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.RetrievedChunk;
import com.jijing.fund.knowledge.port.DocumentReranker;
import java.util.List;

/**
 * 不改变顺序、也不看查询文本的重排器。
 * 候选为空或 {@code topK} 为 0 时返回空列表，表示无命中。
 * {@code topK} 为负时由 {@link java.util.stream.Stream#limit} 抛出 {@link IllegalArgumentException}。
 * 不检查引用字段，缺少来源的切片会原样留下。
 */
public final class NoOpDocumentReranker implements DocumentReranker {
    /**
     * 按原顺序保留前 {@code topK} 条。查询文本被忽略。
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        return candidates.stream().limit(topK).toList();
    }
}
