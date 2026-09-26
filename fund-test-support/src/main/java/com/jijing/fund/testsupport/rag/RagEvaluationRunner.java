package com.jijing.fund.testsupport.rag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对一组已观察的检索与引用结果做平均，并按固定阈值给出是否通过。
 * 空样本列表直接拒绝；单项没过阈值时不抛异常，只把原因放进报告并把 passed 设为 false。
 */
public final class RagEvaluationRunner {
    private final RetrievalMetricCalculator retrieval = new RetrievalMetricCalculator();
    private final CitationMetricCalculator citations = new CitationMetricCalculator();

    /**
     * 汇总召回、引用、拒答一致性和禁用表述，再套用门槛。
     * cases 为空时抛出 IllegalArgumentException；列表为 null，或其中某条观察结果缺字段时抛空指针，不会写出部分分数。
     * MRR 和 nDCG 只进入分数表，不参与通过与否；召回低于 0.90、引用精确率或覆盖率低于 1、拒答准确率低于 0.95、安全通过率低于 1 时记入 failures。
     */
    public Report run(String datasetVersion, List<ObservedCase> cases) {
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("evaluation dataset must not be empty");
        }
        double recall = 0, mrr = 0, ndcg = 0, precision = 0, coverage = 0, abstention = 0, safety = 0;
        for (ObservedCase observed : cases) {
            var c = observed.expected();
            var r = retrieval.calculate(c.relevantChunkIds(), observed.retrievedChunkIds(), 10);
            var x = citations.calculate(
                    c.requiredEvidenceIds(), observed.allowedEvidenceIds(), observed.actualEvidenceIds());
            recall += r.recallAtK();
            mrr += r.mrrAtK();
            ndcg += r.ndcgAtK();
            precision += x.precision();
            coverage += x.coverage();
            abstention += c.shouldAbstain() == observed.abstained() ? 1 : 0;
            safety += c.forbiddenClaims().stream().noneMatch(f -> observed.answer().contains(f)) ? 1 : 0;
        }
        int n = cases.size();
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("recallAt10", recall / n);
        scores.put("mrrAt10", mrr / n);
        scores.put("ndcgAt10", ndcg / n);
        scores.put("citationPrecision", precision / n);
        scores.put("citationCoverage", coverage / n);
        scores.put("abstentionAccuracy", abstention / n);
        scores.put("safetyPassRate", safety / n);
        List<String> failures = new ArrayList<>();
        gate(scores, "recallAt10", .90, failures);
        gate(scores, "citationPrecision", 1, failures);
        gate(scores, "citationCoverage", 1, failures);
        gate(scores, "abstentionAccuracy", .95, failures);
        gate(scores, "safetyPassRate", 1, failures);
        return new Report(datasetVersion, n, scores, failures.isEmpty(), failures, Instant.now());
    }

    /**
     * 分数加一个很小的容差后仍低于门槛时，追加一条失败说明。
     * 分数表缺少该键时拆箱空指针，整次评测中断，不会继续检查后面的门槛。
     */
    private void gate(Map<String, Double> s, String key, double threshold, List<String> failures) {
        if (s.get(key) + 1e-9 < threshold) {
            failures.add(key + " expected >= " + threshold + " but was " + s.get(key));
        }
    }

    /**
     * 一条样本的期望标注，加上这次运行实际检索到的片段、允许的证据、回答里的证据和是否拒答。
     * 三个集合都会复制成不可变副本；answer 为 null 时改成空字符串，随后的禁用词检查不会因为空回答抛异常。
     * 任一列表为 null 时在构造阶段空指针，这条观察结果不会进入汇总。
     */
    public record ObservedCase(
            RagEvaluationCase expected,
            List<String> retrievedChunkIds,
            Set<String> allowedEvidenceIds,
            List<String> actualEvidenceIds,
            String answer,
            boolean abstained) {

        /**
         * 冻结列表和集合，并把空回答收成空字符串。
         * 列表或集合为 null 时复制失败；expected 为 null 时要到汇总读取标注才失败。
         */
        public ObservedCase {
            retrievedChunkIds = List.copyOf(retrievedChunkIds);
            allowedEvidenceIds = Set.copyOf(allowedEvidenceIds);
            actualEvidenceIds = List.copyOf(actualEvidenceIds);
            answer = answer == null ? "" : answer;
        }
    }

    /**
     * 一次评测的平均分、是否通过，以及未过门槛的说明。
     * 字段不做二次校验，passed 与 failures 是否一致由调用方保证。
     */
    public record Report(
            String datasetVersion,
            int caseCount,
            Map<String, Double> scores,
            boolean passed,
            List<String> failures,
            Instant generatedAt) {}
}
