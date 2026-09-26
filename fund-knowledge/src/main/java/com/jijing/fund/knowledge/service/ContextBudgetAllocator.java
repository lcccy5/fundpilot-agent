package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在词元预算和条数上限内挑选检索片段，并合并紧邻切片。
 * <p>
 * 空候选返回空列表。相同 {@code contentSha256} 只保留第一次，重复项不占用预算。
 * 单片 {@code tokenCount} 放不进剩余预算时跳过该片，继续看后面的更小片段，因此结果可以不连续。
 * {@code tokenCount} 按调用方给定的值使用，0 也会被收下且不增加已用预算。
 * 条数达到 {@code topK} 后停止；但停止前仍会尝试把下一片合并进当前最后一片。
 * {@code topK} 小于等于 0 时一片都不会加入。
 * 缺少标题、来源或 URI 不会被剔除。合并时标题、基金代码、文档标题、发布日期和来源取左侧，
 * 页码取两侧极值。合并后的顺序号仍是左侧的顺序号，所以连续三片只会并成「第一片+第二片」再另留第三片。
 */
public final class ContextBudgetAllocator {
    /**
     * 按输入顺序挑选不超过 {@code maxTokens} 且不超过 {@code topK} 条的片段。
     * 候选为 null 时抛出 {@link NullPointerException}。
     */
    public List<RetrievedChunk> allocate(List<RetrievedChunk> candidates, int maxTokens, int topK) {
        List<RetrievedChunk> result = new ArrayList<>();
        Set<String> contentHashes = new HashSet<>();
        int used = 0;
        for (RetrievedChunk candidate : candidates) {
            if (!contentHashes.add(candidate.chunk().contentSha256())) {
                continue;
            }
            int tokens = candidate.chunk().tokenCount();
            if (tokens > maxTokens - used) {
                continue;
            }
            if (!result.isEmpty()
                    && adjacent(result.getLast(), candidate)
                    && result.getLast().chunk().tokenCount() + tokens <= maxTokens) {
                RetrievedChunk previous = result.removeLast();
                result.add(merge(previous, candidate));
                used += tokens;
                continue;
            }
            if (result.size() >= topK) {
                break;
            }
            result.add(candidate);
            used += tokens;
        }
        return List.copyOf(result);
    }

    /**
     * 同一版本、顺序号刚好相差 1，且右侧起始页不超过左侧结束页的下一页时视为相邻。
     */
    private boolean adjacent(RetrievedChunk left, RetrievedChunk right) {
        DocumentChunk earlier = left.chunk();
        DocumentChunk later = right.chunk();
        return earlier.versionId().equals(later.versionId())
                && later.chunkOrder() == earlier.chunkOrder() + 1
                && later.pageStart() <= earlier.pageEnd() + 1;
    }

    /**
     * 用换行拼正文。新标识是两侧标识用 {@code +} 连接，新哈希是拼接正文的 SHA-256。
     * 分数取较大值，名次取较小值，通道按先左后右去重。
     */
    private RetrievedChunk merge(RetrievedChunk left, RetrievedChunk right) {
        DocumentChunk earlier = left.chunk();
        DocumentChunk later = right.chunk();
        Set<String> channels = new LinkedHashSet<>(left.channels());
        channels.addAll(right.channels());
        String content = earlier.content() + "\n" + later.content();
        DocumentChunk chunk = new DocumentChunk(
                earlier.chunkId() + "+" + later.chunkId(),
                earlier.documentId(),
                earlier.versionId(),
                content,
                earlier.headingPath(),
                Math.min(earlier.pageStart(), later.pageStart()),
                Math.max(earlier.pageEnd(), later.pageEnd()),
                earlier.chunkOrder(),
                earlier.tokenCount() + later.tokenCount(),
                earlier.chunkingVersion(),
                KnowledgeHash.sha256(content.getBytes(StandardCharsets.UTF_8)),
                earlier.fundCodes(),
                earlier.documentType(),
                earlier.documentTitle(),
                earlier.publishedDate(),
                earlier.sourceName(),
                earlier.sourceUri());
        return new RetrievedChunk(chunk, Math.max(left.score(), right.score()), Math.min(left.rank(), right.rank()), channels);
    }
}
