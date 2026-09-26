package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 把词法路和向量路的名次融合成一条排序。
 * <p>
 * 某一路为 null 或空列表时视为该路无命中，不抛异常。两路都没有命中时返回空列表。
 * 同一 {@code chunkId} 的分数相加。名次小于等于 0 时改用该路列表中的 1 起下标。
 * 分数相同则按切片标识字典序排，再按 {@code topK} 截断。{@code topK} 为 0 时结果为空；
 * 为负时由流的 {@code limit} 抛出 {@link IllegalArgumentException}。
 * 融合不检查来源、标题或页码，缺少引用的切片仍会留下。
 */
public final class ReciprocalRankFusion {
    private final int rankConstant;

    /**
     * @param rankConstant 倒数排名的平滑常数，必须至少为 1。分数增量是 {@code 1 / (rankConstant + rank)}
     * @throws IllegalArgumentException 常数小于 1
     */
    public ReciprocalRankFusion(int rankConstant) {
        if (rankConstant < 1) {
            throw new IllegalArgumentException("rankConstant must be positive");
        }
        this.rankConstant = rankConstant;
    }

    /**
     * 融合两路结果。词法通道记为 {@code bm25}，向量通道记为 {@code vector}。
     * 输出名次从 1 重新编号，通道集合按名字字典序。
     */
    public List<RetrievedChunk> fuse(List<RetrievedChunk> lexical, List<RetrievedChunk> vector, int topK) {
        Map<String, Accumulator> values = new HashMap<>();
        accumulate(values, lexical, "bm25");
        accumulate(values, vector, "vector");
        List<Accumulator> sorted = values.values().stream()
                .sorted(Comparator.comparingDouble(Accumulator::score).reversed().thenComparing(accumulator -> accumulator.chunk.chunkId()))
                .limit(topK)
                .toList();
        List<RetrievedChunk> result = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Accumulator accumulator = sorted.get(i);
            result.add(new RetrievedChunk(accumulator.chunk, accumulator.score, i + 1, accumulator.channels));
        }
        return List.copyOf(result);
    }

    /**
     * 把一路命中累加进融合表。输入为 null 时直接返回。同一路里重复出现的标识会多次加分。
     */
    private void accumulate(Map<String, Accumulator> values, List<RetrievedChunk> input, String channel) {
        if (input == null) {
            return;
        }
        for (int i = 0; i < input.size(); i++) {
            RetrievedChunk hit = input.get(i);
            int rank = hit.rank() > 0 ? hit.rank() : i + 1;
            Accumulator accumulator = values.computeIfAbsent(hit.chunk().chunkId(), ignored -> new Accumulator(hit.chunk()));
            accumulator.score += 1.0 / (rankConstant + rank);
            accumulator.channels.add(channel);
        }
    }

    /**
     * 同一切片在多路排序中的累计分数和命中通道。通道按字典序保存。
     */
    private static final class Accumulator {
        private final DocumentChunk chunk;
        private final Set<String> channels = new TreeSet<>();
        private double score;

        /**
         * 以第一次见到的切片对象作为最终带回的正文和引用字段。后出现的同标识对象不覆盖它。
         */
        private Accumulator(DocumentChunk chunk) {
            this.chunk = chunk;
        }

        /**
         * 当前累计的倒数排名分数。
         */
        private double score() {
            return score;
        }
    }
}
