package com.jijing.fund.testsupport.rag;

import java.util.List;
import java.util.Set;

/**
 * 按截断后的排序结果计算 Recall、MRR 和 nDCG。
 * 相关集合为空或为 null 时三项都记为 1，表示这条样本没有可错的标注。
 */
public final class RetrievalMetricCalculator {

    /**
     * 只看排序列表的前 k 条，计算命中比例、首个命中的倒数名次，以及折损后的增益比。
     * ranked 为 null 时抛空指针；k 小于等于 0 时 top 为空，召回率为 0，理想增益为 0 时 nDCG 记为 1。
     * 相关集合里的重复 id 只按集合计一次，排序列表里的重复命中在召回里去重，但折损增益会重复累加。
     */
    public RetrievalMetrics calculate(Set<String> relevant, List<String> ranked, int k) {
        if (relevant == null || relevant.isEmpty()) {
            return new RetrievalMetrics(1, 1, 1);
        }
        List<String> top = ranked.stream().limit(k).toList();
        long hits = top.stream().filter(relevant::contains).distinct().count();
        double recall = (double) hits / relevant.size();
        double mrr = 0, dcg = 0;
        for (int i = 0; i < top.size(); i++) {
            if (relevant.contains(top.get(i))) {
                if (mrr == 0) {
                    mrr = 1d / (i + 1);
                }
                dcg += 1d / (Math.log(i + 2) / Math.log(2));
            }
        }
        double ideal = 0;
        for (int i = 0; i < Math.min(k, relevant.size()); i++) {
            ideal += 1d / (Math.log(i + 2) / Math.log(2));
        }
        return new RetrievalMetrics(recall, mrr, ideal == 0 ? 1 : dcg / ideal);
    }

    /**
     * 一次检索的三项分数。
     * 不在这里截断到 0 到 1，计算过程出现除零以外的异常值会原样留下。
     */
    public record RetrievalMetrics(double recallAtK, double mrrAtK, double ndcgAtK) {}
}
