package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.RetrievedChunk;
import com.jijing.fund.knowledge.port.DocumentReranker;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用汉字二元组重叠给候选重新排序的本地试验实现。
 * <p>
 * 查询或正文去掉空白后不足两个 UTF-16 字符时，重叠分为 0，排序只剩原来的分数乘以 0.05。
 * 没有共同二元组不算无命中：候选仍会返回，只是重叠分不增加。候选列表为空时返回空列表。
 * {@code topK} 为 0 时返回空列表；为负时由 {@link java.util.stream.Stream#limit} 抛出
 * {@link IllegalArgumentException}。相等分数保持原顺序。
 * 不读取页码、标题或来源，缺少引用的切片照常参与排序。
 */
public final class ChineseOverlapDocumentReranker implements DocumentReranker {
    /**
     * 按重叠分加上原分数的 5% 降序截取前 {@code topK} 条。返回的仍是输入里的同一批对象，不改写分数字段。
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        Set<String> queryGrams = grams(query);
        return candidates.stream()
                .map(candidate -> Map.entry(candidate, score(queryGrams, grams(candidate.chunk().content())) + candidate.score() * .05))
                .sorted(Map.Entry.<RetrievedChunk, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 二元组集合有一边为空时返回 0，否则返回命中个数除以两边大小的几何平均。
     */
    private double score(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        long hit = left.stream().filter(right::contains).count();
        return (double) hit / Math.sqrt((double) left.size() * right.size());
    }

    /**
     * 去掉全部空白后，按相邻两个 UTF-16 字符取二元组。null 当作空串，长度小于 2 时返回空集。
     */
    private Set<String> grams(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", "");
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < text.length() - 1; i++) {
            result.add(text.substring(i, i + 2));
        }
        return result;
    }
}
