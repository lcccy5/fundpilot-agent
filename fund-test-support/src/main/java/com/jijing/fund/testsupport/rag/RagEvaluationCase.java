package com.jijing.fund.testsupport.rag;

import java.util.List;
import java.util.Set;

/**
 * 一条检索评测样本：问题、期望命中的片段和证据，以及应拒绝回答时的约束。
 * 集合或列表为 null 时收成空集合，避免下游把“没有标注”当成空指针；id 和问题本身不做校验，空字符串也会被保留。
 */
public record RagEvaluationCase(
        String id,
        String question,
        Set<String> fundCodes,
        Set<String> documentTypes,
        Set<String> relevantChunkIds,
        Set<String> requiredEvidenceIds,
        List<String> expectedAnswerPoints,
        List<String> forbiddenClaims,
        boolean shouldAbstain) {

    /**
     * 复制调用方传入的集合，避免外部随后改动样本。
     * 为 null 的集合和列表变成空集合；id、question 为 null 时照原样保存，比较阶段才可能失败。
     */
    public RagEvaluationCase {
        fundCodes = copy(fundCodes);
        documentTypes = copy(documentTypes);
        relevantChunkIds = copy(relevantChunkIds);
        requiredEvidenceIds = copy(requiredEvidenceIds);
        expectedAnswerPoints = expectedAnswerPoints == null ? List.of() : List.copyOf(expectedAnswerPoints);
        forbiddenClaims = forbiddenClaims == null ? List.of() : List.copyOf(forbiddenClaims);
    }

    /**
     * 把可空集合收成不可变副本。
     * 入参为 null 时返回空集；集合里的 null 元素会在复制时抛出空指针，整条样本创建失败。
     */
    private static Set<String> copy(Set<String> v) {
        return v == null ? Set.of() : Set.copyOf(v);
    }
}
