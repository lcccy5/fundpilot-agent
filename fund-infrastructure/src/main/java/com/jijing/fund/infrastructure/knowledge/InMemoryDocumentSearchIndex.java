package com.jijing.fund.infrastructure.knowledge;

import com.jijing.fund.knowledge.api.KnowledgeSearchQuery;
import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.IndexedChunk;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import com.jijing.fund.knowledge.port.DocumentSearchIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * 进程内检索，供开发和测试使用。没有网络，因此没有超时、空响应或连接失败。
 * 相同 chunk id 再次写入会覆盖。得分为 0 的命中被丢弃。生产环境应换 Elasticsearch 适配器。
 */
public final class InMemoryDocumentSearchIndex implements DocumentSearchIndex {
    private final Map<String, IndexedChunk> values = new ConcurrentHashMap<>();
    private final String version;

    /** 版本字符串原样作为索引版本，不和物理索引名拼接。 */
    public InMemoryDocumentSearchIndex(String version) {
        this.version = version;
    }

    /** 调用方用它判断结果来自哪一版切分与嵌入配置。 */
    @Override
    public String indexVersion() {
        return version;
    }

    /** 以 chunk id 为键覆盖写入，重复提交不会保留旧向量。 */
    @Override
    public void index(List<IndexedChunk> chunks) {
        chunks.forEach(chunk -> values.put(chunk.chunk().chunkId(), chunk));
    }

    /** 同一文档只保留指定版本，其他版本从内存删除。 */
    @Override
    public void activate(String documentId, String versionId) {
        values.entrySet().removeIf(entry -> entry.getValue().chunk().documentId().equals(documentId)
                && !entry.getValue().chunk().versionId().equals(versionId));
    }

    /** 用查询词与正文的词重叠比例近似 BM25，空查询得分为 0。 */
    @Override
    public List<RetrievedChunk> lexicalSearch(KnowledgeSearchQuery query, int topK) {
        Set<String> terms = terms(query.query());
        return ranked(query, topK, indexed -> {
            Set<String> content = terms(indexed.chunk().content());
            long overlap = terms.stream().filter(content::contains).count();
            return terms.isEmpty() ? 0 : (double) overlap / terms.size();
        }, "bm25");
    }

    /** 向量长度不一致或任一侧为零向量时相似度为 0，该条不会进入结果。 */
    @Override
    public List<RetrievedChunk> vectorSearch(KnowledgeSearchQuery query, float[] vector, int topK) {
        return ranked(query, topK, indexed -> cosine(vector, indexed.embedding()), "vector");
    }

    /** 先过滤再打分，分数降序，同分按 chunk id 升序，最后截断到 topK。 */
    private List<RetrievedChunk> ranked(KnowledgeSearchQuery query, int topK,
            ToDoubleFunction<IndexedChunk> score, String channel) {
        List<IndexedChunk> filtered = values.values().stream().filter(value -> matches(query, value.chunk())).toList();
        List<Map.Entry<IndexedChunk, Double>> scored = filtered.stream()
                .map(value -> Map.entry(value, score.applyAsDouble(value)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<IndexedChunk, Double>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().chunk().chunkId()))
                .limit(topK)
                .toList();
        List<RetrievedChunk> result = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            Map.Entry<IndexedChunk, Double> entry = scored.get(i);
            result.add(new RetrievedChunk(entry.getKey().chunk(), entry.getValue(), i + 1, Set.of(channel)));
        }
        return result;
    }

    /**
     * 基金代码、文档类型和发布日期都是硬过滤。查询未给的条件不生效；
     * 文档没有发布日期时，只要查询带了日期边界就排除。
     */
    private boolean matches(KnowledgeSearchQuery query, DocumentChunk chunk) {
        if (!query.fundCodes().isEmpty() && Collections.disjoint(query.fundCodes(), chunk.fundCodes())) {
            return false;
        }
        if (!query.documentTypes().isEmpty() && !query.documentTypes().contains(chunk.documentType())) {
            return false;
        }
        if (query.publishedAfter() != null
                && (chunk.publishedDate() == null || chunk.publishedDate().isBefore(query.publishedAfter()))) {
            return false;
        }
        return query.publishedBefore() == null
                || (chunk.publishedDate() != null && !chunk.publishedDate().isAfter(query.publishedBefore()));
    }

    /** 同时收集按非字母数字切开的词，以及相邻两字，便于中文没有空格时仍能重叠。 */
    private Set<String> terms(String value) {
        Set<String> result = new HashSet<>();
        for (String term : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!term.isBlank()) {
                result.add(term);
            }
        }
        for (int i = 0; i < value.length() - 1; i++) {
            String pair = value.substring(i, i + 2);
            if (pair.codePoints().allMatch(Character::isLetterOrDigit)) {
                result.add(pair);
            }
        }
        return result;
    }

    /** 长度不同、空向量或零向量都返回 0，避免 NaN 进入排序。 */
    private double cosine(float[] left, float[] right) {
        if (left.length == 0 || left.length != right.length) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
