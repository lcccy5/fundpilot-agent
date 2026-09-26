package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.api.KnowledgeSearchQuery;
import com.jijing.fund.knowledge.api.KnowledgeSearchResult;
import com.jijing.fund.knowledge.api.KnowledgeSearchUseCase;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import com.jijing.fund.knowledge.port.DocumentEmbeddingPort;
import com.jijing.fund.knowledge.port.DocumentReranker;
import com.jijing.fund.knowledge.port.DocumentSearchIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 词法检索与向量检索并行，再用倒数排名融合、可选重排和上下文预算收成一条结果。
 * <p>
 * 两路都没有命中时，融合、重排和预算都得到空列表，仍返回新的检索号和空警告，不抛「无命中」异常。
 * 查询为空、只含空白、长于 500 个 UTF-16 字符、基金代码不是 6 位数字，或发布日期区间颠倒时，
 * 在访问索引前抛出 {@link IllegalArgumentException}。超长中文查询按 {@link String#length()} 计数。
 * 重排抛出任何 {@link RuntimeException}（包括负的 {@code topK}）时，改用融合结果并附加警告
 * {@code RERANK_DEGRADED}。重排正常返回空列表时不附加该警告，调用方看到的就是无命中。
 * 预算使用查询里已经钳制过的 {@code topK}。缺少页码、标题或来源 URI 的切片不会被丢掉，也不另加警告。
 */
public final class HybridKnowledgeSearchService implements KnowledgeSearchUseCase {
    private final DocumentEmbeddingPort embeddings;
    private final DocumentSearchIndex index;
    private final DocumentReranker reranker;
    private final ReciprocalRankFusion rrf;
    private final ContextBudgetAllocator budget = new ContextBudgetAllocator();
    private final int channelTopK;
    private final int fusedTopK;
    private final int rerankTopK;
    private final int maxContextTokens;

    /**
     * @param embeddings 只对查询文本取向量；文档向量在入库时已经写入索引
     * @param index 提供词法路和向量路。某一路返回 null 时，融合器把它当作该路无命中
     * @param reranker 对融合结果再截取。失败时被降级，不会让整次检索失败
     * @param channelTopK 每一路单独取回的条数
     * @param rrfK 倒数排名平滑常数，必须为正
     * @param fusedTopK 融合后保留的条数
     * @param rerankTopK 交给重排器的条数。为 0 时重排结果为空，整次检索变成无命中；为负时触发降级
     * @param maxContextTokens 交给预算分配器的词元上限
     */
    public HybridKnowledgeSearchService(
            DocumentEmbeddingPort embeddings,
            DocumentSearchIndex index,
            DocumentReranker reranker,
            int channelTopK,
            int rrfK,
            int fusedTopK,
            int rerankTopK,
            int maxContextTokens) {
        this.embeddings = embeddings;
        this.index = index;
        this.reranker = reranker;
        this.rrf = new ReciprocalRankFusion(rrfK);
        this.channelTopK = channelTopK;
        this.fusedTopK = fusedTopK;
        this.rerankTopK = rerankTopK;
        this.maxContextTokens = maxContextTokens;
    }

    /**
     * 执行一次混合检索。返回的切片已经过预算裁剪，可能少于重排结果，也可能因相邻合并而把两片拼成一片。
     */
    @Override
    public KnowledgeSearchResult search(KnowledgeSearchQuery query) {
        validate(query);
        List<RetrievedChunk> lexical = index.lexicalSearch(query, channelTopK);
        float[] vector = embeddings.embedQuery(query.query());
        List<RetrievedChunk> semantic = index.vectorSearch(query, vector, channelTopK);
        List<RetrievedChunk> fused = rrf.fuse(lexical, semantic, fusedTopK);
        List<String> warnings = new ArrayList<>();
        List<RetrievedChunk> reranked;
        try {
            reranked = reranker.rerank(query.query(), fused, rerankTopK);
        } catch (RuntimeException ex) {
            warnings.add("RERANK_DEGRADED");
            reranked = fused;
        }
        return new KnowledgeSearchResult(
                UUID.randomUUID().toString(),
                budget.allocate(reranked, maxContextTokens, query.topK()),
                warnings);
    }

    /**
     * 拒绝空查询、超长查询、非法基金代码和颠倒的发布日期。日期两端相等是允许的。
     * 基金代码集合为空时不检查。查询长度按 UTF-16 字符计，上限 500，含 500。
     */
    private void validate(KnowledgeSearchQuery query) {
        if (query == null || query.query() == null || query.query().isBlank() || query.query().length() > 500) {
            throw new IllegalArgumentException("query must contain 1 to 500 characters");
        }
        if (query.fundCodes().stream().anyMatch(code -> !code.matches("\\d{6}"))) {
            throw new IllegalArgumentException("fundCode must be 6 digits");
        }
        if (query.publishedAfter() != null
                && query.publishedBefore() != null
                && query.publishedAfter().isAfter(query.publishedBefore())) {
            throw new IllegalArgumentException("publishedAfter must not be after publishedBefore");
        }
    }
}
