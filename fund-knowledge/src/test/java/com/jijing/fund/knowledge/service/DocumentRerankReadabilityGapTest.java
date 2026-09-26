package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 锁定重排在无命中、无重叠和非法条数上的现有行为。
 */
class DocumentRerankReadabilityGapTest {
    private final ChineseOverlapDocumentReranker overlap = new ChineseOverlapDocumentReranker();
    private final NoOpDocumentReranker noop = new NoOpDocumentReranker();

    /**
     * 没有候选时两种重排都返回空列表，不另造命中。
     */
    @Test
    void emptyCandidatesAreNoHit() {
        assertThat(overlap.rerank("投资策略", List.of(), 5)).isEmpty();
        assertThat(noop.rerank("投资策略", List.of(), 5)).isEmpty();
    }

    /**
     * 没有共同二元组时仍返回候选，只按原来的分数排序。
     */
    @Test
    void noSharedBigramStillReturnsCandidates() {
        RetrievedChunk low = hit("low", "丙丁戊己", 0.2);
        RetrievedChunk high = hit("high", "甲乙说明", 0.8);
        assertThat(overlap.rerank("子丑寅卯", List.of(low, high), 5))
                .extracting(item -> item.chunk().chunkId())
                .containsExactly("high", "low");
    }

    /**
     * 查询短到取不出二元组时，重叠分为 0，顺序仍由原分数决定。
     */
    @Test
    void queryShorterThanTwoCharactersDoesNotDropCandidates() {
        RetrievedChunk low = hit("low", "投资策略说明", 0.1);
        RetrievedChunk high = hit("high", "完全不同的正文", 0.9);
        assertThat(overlap.rerank("基", List.of(low, high), 5))
                .extracting(item -> item.chunk().chunkId())
                .containsExactly("high", "low");
        assertThat(overlap.rerank(null, List.of(low, high), 1))
                .extracting(item -> item.chunk().chunkId())
                .containsExactly("high");
    }

    /**
     * 有重叠的切片可以压过原分数更高但没有共同二元组的切片。缺少来源不影响排序。
     */
    @Test
    void sharedBigramOutranksAHigherOriginalScore() {
        RetrievedChunk cited = hit("cited", "策略说明", 0.1);
        RetrievedChunk unrelated = hit("other", "甲乙丙丁", 0.9);
        assertThat(overlap.rerank("投资 策略", List.of(unrelated, cited), 2))
                .extracting(item -> item.chunk().chunkId())
                .containsExactly("cited", "other");
        assertThat(cited.chunk().sourceUri()).isNull();
    }

    /**
     * 条数为 0 时视为无命中；负数在截断时被拒绝。
     */
    @Test
    void topKZeroIsEmptyAndNegativeTopKIsRejected() {
        List<RetrievedChunk> candidates = List.of(hit("a", "投资策略", 1));
        assertThat(overlap.rerank("投资策略", candidates, 0)).isEmpty();
        assertThat(noop.rerank("投资策略", candidates, 0)).isEmpty();
        assertThatThrownBy(() -> overlap.rerank("投资策略", candidates, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> noop.rerank("投资策略", candidates, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 不重排时只按原顺序截断，空白查询也不会把结果清空。
     */
    @Test
    void noOpKeepsOrderAndIgnoresTheQuery() {
        RetrievedChunk first = hit("a", "甲乙", 0.1);
        RetrievedChunk second = hit("b", "丙丁", 0.9);
        assertThat(noop.rerank("  ", List.of(first, second), 1))
                .extracting(item -> item.chunk().chunkId())
                .containsExactly("a");
    }

    /**
     * 造一条带来源缺失的命中。正文和原分数由用例指定。
     */
    private static RetrievedChunk hit(String id, String content, double score) {
        DocumentChunk chunk = new DocumentChunk(
                id, "doc", "ver", content, "", 1, 1, 1, 10, "v1", id,
                Set.of(), FundDocumentType.OTHER, null, null, null, null);
        return new RetrievedChunk(chunk, score, 1, Set.of("bm25"));
    }
}
