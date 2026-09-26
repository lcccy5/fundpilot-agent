package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定上下文预算在超限、重复正文、条数上限和引用合并上的现有行为。
 */
class ContextBudgetAllocatorReadabilityGapTest {
    private final ContextBudgetAllocator allocator = new ContextBudgetAllocator();

    /**
     * 没有候选时结果为空。
     */
    @Test
    void emptyCandidatesReturnEmpty() {
        assertThat(allocator.allocate(List.of(), 100, 5)).isEmpty();
    }

    /**
     * 放不进剩余预算的大片被跳过，后面的小片仍可入选。
     */
    @Test
    void oversizedChunkIsSkippedAndALaterSmallerChunkCanFit() {
        List<RetrievedChunk> result = allocator.allocate(List.of(
                hit("big", "h1", 80, 1, 1, 1, "甲", "https://left"),
                hit("small", "h2", 10, 2, 2, 2, "乙", "https://right")), 50, 5);
        assertThat(result).extracting(item -> item.chunk().chunkId()).containsExactly("small");
    }

    /**
     * 相同正文哈希不占预算，后面不同的切片仍按剩余预算判断。
     */
    @Test
    void duplicateContentDoesNotConsumeBudget() {
        List<RetrievedChunk> result = allocator.allocate(List.of(
                hit("a", "same", 40, 1, 1, 1, "甲", null),
                hit("b", "same", 40, 2, 2, 2, "乙", "https://b"),
                hit("c", "other", 50, 3, 3, 3, "丙", null)), 100, 5);
        assertThat(result).extracting(item -> item.chunk().chunkId()).containsExactly("a", "c");
    }

    /**
     * 条数上限小于等于 0 时一片都不收。
     */
    @Test
    void nonPositiveTopKReturnsEmpty() {
        RetrievedChunk only = hit("a", "h", 10, 1, 1, 1, "甲", null);
        assertThat(allocator.allocate(List.of(only), 100, 0)).isEmpty();
        assertThat(allocator.allocate(List.of(only), 100, -3)).isEmpty();
    }

    /**
     * 相邻两片合并后保留左侧引用，页码取并集，右侧标题不会覆盖左侧。
     */
    @Test
    void mergeKeepsTheLeftCitation() {
        List<RetrievedChunk> result = allocator.allocate(List.of(
                hit("left", "h1", 10, 1, 1, 1, "甲标题", null),
                hit("right", "h2", 10, 2, 2, 4, "乙标题", "https://right")), 100, 5);
        assertThat(result).hasSize(1);
        DocumentChunk merged = result.getFirst().chunk();
        assertThat(merged.chunkId()).isEqualTo("left+right");
        assertThat(merged.headingPath()).isEqualTo("甲标题");
        assertThat(merged.sourceUri()).isNull();
        assertThat(merged.sourceName()).isEqualTo("左侧来源");
        assertThat(merged.documentTitle()).isEqualTo("左侧文档");
        assertThat(merged.pageStart()).isEqualTo(1);
        assertThat(merged.pageEnd()).isEqualTo(4);
        assertThat(merged.content()).isEqualTo("正文left\n正文right");
    }

    /**
     * 连续三片只会合并前两片，第三片因合并后的顺序号没有前进而单独留下。
     */
    @Test
    void threeAdjacentChunksDoNotCollapseIntoOne() {
        List<RetrievedChunk> result = allocator.allocate(List.of(
                hit("a", "ha", 4, 1, 1, 1, "甲", "https://a"),
                hit("b", "hb", 4, 2, 2, 2, "乙", "https://b"),
                hit("c", "hc", 4, 3, 3, 3, "丙", "https://c")), 100, 5);
        assertThat(result).extracting(item -> item.chunk().chunkId()).containsExactly("a+b", "c");
        assertThat(result.getFirst().chunk().headingPath()).isEqualTo("甲");
    }

    /**
     * 达到条数上限前仍会尝试合并；合并不了的下一片被截掉，即使预算还够。
     */
    @Test
    void topKStopsAfterTheMergeAttempt() {
        List<RetrievedChunk> result = allocator.allocate(List.of(
                hit("a", "ha", 4, 1, 1, 1, "甲", null),
                hit("b", "hb", 4, 2, 2, 2, "乙", "https://b"),
                hit("c", "hc", 4, 3, 3, 3, "丙", "https://c")), 100, 1);
        assertThat(result).extracting(item -> item.chunk().chunkId()).containsExactly("a+b");
    }

    /**
     * 造一条可合并的命中。来源名称和文档标题固定成左侧字样，便于断言合并后谁被留下。
     */
    private static RetrievedChunk hit(
            String id, String hash, int tokens, int order, int pageStart, int pageEnd, String heading, String sourceUri) {
        DocumentChunk chunk = new DocumentChunk(
                id,
                "doc",
                "ver",
                "正文" + id,
                heading,
                pageStart,
                pageEnd,
                order,
                tokens,
                "v1",
                hash,
                Set.of("000001"),
                FundDocumentType.QUARTERLY_REPORT,
                "左侧文档",
                LocalDate.of(2026, 6, 30),
                "左侧来源",
                sourceUri);
        return new RetrievedChunk(chunk, order, order, Set.of("bm25"));
    }
}
