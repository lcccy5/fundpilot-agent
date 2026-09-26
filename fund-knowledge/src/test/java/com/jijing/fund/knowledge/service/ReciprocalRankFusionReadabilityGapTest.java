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
 * 锁定融合在无命中、非法名次和相同分数上的现有行为。
 */
class ReciprocalRankFusionReadabilityGapTest {
    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion(60);

    /**
     * 两路都空，或某一路为 null，都表示该路无命中，结果为空或只剩另一路。
     */
    @Test
    void emptyOrNullChannelsAreNoHit() {
        assertThat(fusion.fuse(List.of(), List.of(), 10)).isEmpty();
        assertThat(fusion.fuse(null, null, 10)).isEmpty();
        RetrievedChunk only = hit("only", 1);
        assertThat(fusion.fuse(null, List.of(only), 5))
                .extracting(item -> item.chunk().chunkId())
                .containsExactly("only");
        assertThat(fusion.fuse(null, List.of(only), 5).getFirst().channels()).containsExactly("vector");
    }

    /**
     * 名次小于等于 0 时改用列表位置，不把 0 带进分母以外的公式。
     */
    @Test
    void nonPositiveRankUsesTheListPosition() {
        RetrievedChunk unranked = hit("a", 0);
        RetrievedChunk explicit = hit("b", 1);
        double fromZero = fusion.fuse(List.of(unranked), List.of(), 5).getFirst().score();
        double fromOne = fusion.fuse(List.of(explicit), List.of(), 5).getFirst().score();
        assertThat(fromZero).isEqualTo(fromOne).isEqualTo(1.0 / 61);
    }

    /**
     * 分数相同时按切片标识字典序，而不是按输入顺序。缺少来源的切片仍保留。
     */
    @Test
    void equalScoresBreakTiesByChunkId() {
        List<RetrievedChunk> fused = fusion.fuse(List.of(hit("b", 1)), List.of(hit("a", 1)), 10);
        assertThat(fused).extracting(item -> item.chunk().chunkId()).containsExactly("a", "b");
        assertThat(fused).allSatisfy(item -> {
            assertThat(item.chunk().sourceUri()).isNull();
            assertThat(item.chunk().headingPath()).isEmpty();
        });
    }

    /**
     * 条数为 0 时没有结果。平滑常数小于 1 时拒绝构造。
     */
    @Test
    void topKZeroIsEmptyAndRankConstantMustBePositive() {
        assertThat(fusion.fuse(List.of(hit("a", 1)), List.of(), 0)).isEmpty();
        assertThatThrownBy(() -> new ReciprocalRankFusion(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rankConstant must be positive");
    }

    /**
     * 造一条词法或向量命中。来源和标题故意留空。
     */
    private static RetrievedChunk hit(String id, int rank) {
        DocumentChunk chunk = new DocumentChunk(
                id, "doc", "ver", "正文", "", 1, 1, 1, 4, "v1", id,
                Set.of(), FundDocumentType.OTHER, null, null, null, null);
        return new RetrievedChunk(chunk, 1, rank, Set.of("bm25"));
    }
}
