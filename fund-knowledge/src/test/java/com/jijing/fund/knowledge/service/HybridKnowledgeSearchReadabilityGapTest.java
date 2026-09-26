package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.api.KnowledgeSearchQuery;
import com.jijing.fund.knowledge.api.KnowledgeSearchResult;
import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import com.jijing.fund.knowledge.port.DocumentEmbeddingPort;
import com.jijing.fund.knowledge.port.DocumentReranker;
import com.jijing.fund.knowledge.port.DocumentSearchIndex;
import com.jijing.fund.knowledge.domain.IndexedChunk;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 锁定混合检索在无命中、超长查询、缺失引用、重排降级和上下文预算上的现有行为。
 */
class HybridKnowledgeSearchReadabilityGapTest {
    /**
     * 两路都没有命中时返回空切片和空警告，检索号仍然存在。
     */
    @Test
    void noHitReturnsAnEmptyResultWithoutAWarning() {
        KnowledgeSearchResult result = search(List.of(), List.of(), new NoOpDocumentReranker(), 5, 100, 6);
        assertThat(result.retrievalId()).isNotBlank();
        assertThat(result.chunks()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    /**
     * 词法路返回 null 时只保留向量路，不把 null 当成失败。
     */
    @Test
    void nullLexicalChannelIsTreatedAsNoHit() {
        RetrievedChunk vectorHit = hit("vec", "vh", 4, 1, 1, 1, "", null);
        KnowledgeSearchResult result = search(null, List.of(vectorHit), new NoOpDocumentReranker(), 5, 100, 6);
        assertThat(result.chunks()).extracting(item -> item.chunk().chunkId()).containsExactly("vec");
        assertThat(result.warnings()).isEmpty();
    }

    /**
     * 超过 500 个字符的中文查询在访问索引前被拒绝；刚好 500 个字符可以检索。
     */
    @Test
    void overlongChineseQueryIsRejectedAndFiveHundredCharactersAreAccepted() {
        CountingIndex index = new CountingIndex(List.of(), List.of());
        HybridKnowledgeSearchService service = service(index, new NoOpDocumentReranker(), 5, 100);
        assertThatThrownBy(() -> service.search(query("甲".repeat(501), 6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 to 500");
        assertThat(index.searches).isZero();
        service.search(query("甲".repeat(500), 6));
        assertThat(index.searches).isEqualTo(2);
    }

    /**
     * 空白查询、非法基金代码和颠倒的日期都在检索前被拒绝。
     */
    @Test
    void blankQueryIllegalFundCodeAndInvertedDatesAreRejected() {
        HybridKnowledgeSearchService service = service(new CountingIndex(List.of(), List.of()), new NoOpDocumentReranker(), 5, 100);
        assertThatThrownBy(() -> service.search(query("  ", 6))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(new KnowledgeSearchQuery(
                "收益", Set.of("12345"), Set.of(), null, null, 6))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(new KnowledgeSearchQuery(
                "收益", Set.of(), Set.of(), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 1, 1), 6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publishedAfter");
    }

    /**
     * 缺少标题和来源的切片照常返回，不追加引用警告。
     */
    @Test
    void missingCitationIsReturnedWithoutAWarning() {
        RetrievedChunk uncited = hit("bare", "bare-hash", 4, 1, 1, 1, "", null);
        KnowledgeSearchResult result = search(List.of(uncited), List.of(), new NoOpDocumentReranker(), 5, 100, 6);
        assertThat(result.warnings()).isEmpty();
        DocumentChunk chunk = result.chunks().getFirst().chunk();
        assertThat(chunk.sourceUri()).isNull();
        assertThat(chunk.sourceName()).isNull();
        assertThat(chunk.documentTitle()).isNull();
        assertThat(chunk.headingPath()).isEmpty();
    }

    /**
     * 重排抛出异常时保留融合结果，并只追加降级警告。
     */
    @Test
    void rerankFailureDegradesToTheFusedList() {
        RetrievedChunk hit = hit("keep", "keep-hash", 4, 1, 1, 1, "标题", "https://example.test/a");
        KnowledgeSearchResult result = search(List.of(hit), List.of(), (query, candidates, topK) -> {
            throw new IllegalStateException("reranker down");
        }, 5, 100, 6);
        assertThat(result.warnings()).containsExactly("RERANK_DEGRADED");
        assertThat(result.chunks()).extracting(item -> item.chunk().chunkId()).containsExactly("keep");
    }

    /**
     * 重排条数为 0 时正常返回空列表，不视为降级；负数会触发降级并退回融合结果。
     */
    @Test
    void zeroRerankTopKIsAnEmptyHitAndNegativeRerankTopKDegrades() {
        RetrievedChunk hit = hit("keep", "keep-hash", 4, 1, 1, 1, "标题", null);
        KnowledgeSearchResult zero = search(List.of(hit), List.of(), new NoOpDocumentReranker(), 0, 100, 6);
        assertThat(zero.chunks()).isEmpty();
        assertThat(zero.warnings()).isEmpty();
        KnowledgeSearchResult negative = search(List.of(hit), List.of(), new NoOpDocumentReranker(), -1, 100, 6);
        assertThat(negative.warnings()).containsExactly("RERANK_DEGRADED");
        assertThat(negative.chunks()).extracting(item -> item.chunk().chunkId()).containsExactly("keep");
    }

    /**
     * 查询条数小于等于 0 时按 6 条预算，大于 10 时按 10 条预算。
     */
    @Test
    void queryTopKIsClampedBeforeTheContextBudget() {
        List<RetrievedChunk> hits = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            hits.add(hit("c" + i, "hash-" + i, 1, i + 1, (i + 1) * 10, (i + 1) * 10, "标题", null));
        }
        assertThat(search(hits, List.of(), new NoOpDocumentReranker(), 20, 100, 0).chunks()).hasSize(6);
        assertThat(search(hits, List.of(), new NoOpDocumentReranker(), 20, 100, 99).chunks()).hasSize(10);
    }

    /**
     * 单片超过剩余预算时被跳过，后面更小的切片仍进入结果。
     */
    @Test
    void contextBudgetSkipsAnOversizedChunkInsideSearch() {
        List<RetrievedChunk> hits = List.of(
                hit("big", "big-hash", 4, 1, 1, 1, "甲", null),
                hit("also-big", "also-hash", 4, 2, 9, 9, "乙", null),
                hit("small", "small-hash", 1, 3, 10, 10, "丙", "https://example.test/c"));
        KnowledgeSearchResult result = search(hits, List.of(), new NoOpDocumentReranker(), 10, 5, 6);
        assertThat(result.chunks()).extracting(item -> item.chunk().chunkId()).containsExactly("big", "small");
        assertThat(result.warnings()).isEmpty();
    }

    /**
     * 用给定的两路结果、重排器和预算组装检索服务。
     */
    private static KnowledgeSearchResult search(
            List<RetrievedChunk> lexical,
            List<RetrievedChunk> vector,
            DocumentReranker reranker,
            int rerankTopK,
            int maxTokens,
            int topK) {
        return service(new CountingIndex(lexical, vector), reranker, rerankTopK, maxTokens).search(query("收益风险", topK));
    }

    /**
     * 固定通道条数和融合条数，只让重排条数和预算随用例变化。
     */
    private static HybridKnowledgeSearchService service(
            DocumentSearchIndex index, DocumentReranker reranker, int rerankTopK, int maxTokens) {
        return new HybridKnowledgeSearchService(new FixedEmbeddings(), index, reranker, 20, 60, 20, rerankTopK, maxTokens);
    }

    /**
     * 组装一条只带查询文本和条数的检索条件。
     */
    private static KnowledgeSearchQuery query(String text, int topK) {
        return new KnowledgeSearchQuery(text, Set.of(), Set.of(), null, null, topK);
    }

    /**
     * 造一条检索命中。页码故意拉开，避免预算把它们当成相邻切片合并。
     */
    private static RetrievedChunk hit(
            String id, String hash, int tokens, int order, int pageStart, int pageEnd, String heading, String sourceUri) {
        DocumentChunk chunk = new DocumentChunk(
                id, "doc", "ver", "正文" + id, heading, pageStart, pageEnd, order, tokens, "v1", hash,
                Set.of(), FundDocumentType.OTHER, null, null, null, sourceUri);
        return new RetrievedChunk(chunk, 1, order, Set.of("bm25"));
    }

    /**
     * 查询向量固定为一个数，文档向量在这些用例里不会被调用。
     */
    private static final class FixedEmbeddings implements DocumentEmbeddingPort {
        /**
         * 返回固定的向量版本。
         */
        @Override
        public String version() {
            return "emb-v1";
        }

        /**
         * 这些检索用例不给文档重新算向量。
         */
        @Override
        public List<float[]> embed(List<String> texts) {
            throw new AssertionError("document embed is not used");
        }

        /**
         * 返回一个非空查询向量，让向量路能够被调用。
         */
        @Override
        public float[] embedQuery(String text) {
            return new float[] {1.0f};
        }
    }

    /**
     * 按预设返回两路命中，并统计检索方法被调用的次数。
     */
    private static final class CountingIndex implements DocumentSearchIndex {
        private final List<RetrievedChunk> lexical;
        private final List<RetrievedChunk> vector;
        private int searches;

        /**
         * @param lexical 词法路，可以为 null，用来观察融合器如何处理空引用
         * @param vector 向量路
         */
        private CountingIndex(List<RetrievedChunk> lexical, List<RetrievedChunk> vector) {
            this.lexical = lexical;
            this.vector = vector;
        }

        /**
         * 返回固定索引版本。
         */
        @Override
        public String indexVersion() {
            return "index-v1";
        }

        /**
         * 检索用例不写索引。
         */
        @Override
        public void index(List<IndexedChunk> chunks) {
            throw new AssertionError("index is not used");
        }

        /**
         * 检索用例不激活版本。
         */
        @Override
        public void activate(String documentId, String versionId) {
            throw new AssertionError("activate is not used");
        }

        /**
         * 返回预设的词法命中，并把一次词法检索计入次数。
         */
        @Override
        public List<RetrievedChunk> lexicalSearch(KnowledgeSearchQuery query, int topK) {
            searches++;
            return lexical;
        }

        /**
         * 返回预设的向量命中，并把一次向量检索计入次数。
         */
        @Override
        public List<RetrievedChunk> vectorSearch(KnowledgeSearchQuery query, float[] queryEmbedding, int topK) {
            searches++;
            return vector;
        }
    }
}
