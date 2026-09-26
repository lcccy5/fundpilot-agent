package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认预算会丢掉重复正文，并且放不进剩余预算的切片不会把前面的结果挤掉。
 */
class ContextBudgetAllocatorTest {
    /**
     * 相同哈希只留第一条；随后 80 个词元放不进剩余的 60，结果只剩第一条。
     */
    @Test
    void respectsTokenBudgetAndRemovesDuplicateContent() {
        RetrievedChunk one = hit("a", "same", 40, 1);
        RetrievedChunk duplicate = hit("b", "same", 40, 2);
        RetrievedChunk large = hit("c", "large", 80, 3);
        List<RetrievedChunk> result = new ContextBudgetAllocator().allocate(List.of(one, duplicate, large), 100, 5);
        assertThat(result).extracting(item -> item.chunk().chunkId()).containsExactly("a");
    }

    /**
     * 造一条页码相同、因此不会被当成相邻切片合并的命中。
     */
    private RetrievedChunk hit(String id, String hash, int tokens, int rank) {
        DocumentChunk chunk = new DocumentChunk(
                id, "d", "v", id, "", 1, 1, rank, tokens, "v1", hash,
                Set.of(), FundDocumentType.OTHER, "t", null, "s", null);
        return new RetrievedChunk(chunk, 1, rank, Set.of("bm25"));
    }
}
