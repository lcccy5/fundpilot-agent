package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认两路都出现的切片得分更高，并且输出顺序稳定。
 */
class ReciprocalRankFusionTest {
    /**
     * 同时出现在词法路和向量路的切片排在只出现一次的切片前面。
     */
    @Test
    void rewardsChunksReturnedByBothChannelsAndKeepsStableOrder() {
        RetrievedChunk lexicalA = hit(chunk("a"), 1, "bm25");
        RetrievedChunk lexicalB = hit(chunk("b"), 2, "bm25");
        RetrievedChunk vectorC = hit(chunk("c"), 1, "vector");
        List<RetrievedChunk> result = new ReciprocalRankFusion(60)
                .fuse(List.of(lexicalA, lexicalB), List.of(hit(chunk("b"), 2, "vector"), vectorC), 10);
        assertThat(result).extracting(item -> item.chunk().chunkId()).containsExactly("b", "a", "c");
        assertThat(result.getFirst().channels()).containsExactlyInAnyOrder("bm25", "vector");
    }

    /**
     * 包装一条带名次和通道的命中。分数不参与融合。
     */
    private RetrievedChunk hit(DocumentChunk chunk, int rank, String channel) {
        return new RetrievedChunk(chunk, 1, rank, Set.of(channel));
    }

    /**
     * 造一条只靠标识区分的切片。
     */
    private DocumentChunk chunk(String id) {
        return new DocumentChunk(
                id, "d", "v", "content-" + id, "", 1, 1, 1, 10, "c1", id,
                Set.of("000001"), FundDocumentType.QUARTERLY_REPORT, "title", null, "test", null);
    }
}
