package com.jijing.fund.testsupport.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 用一条完全命中的样本确认质量门槛会判为通过。
 * 不覆盖空数据集、缺证据和禁用表述；那些路径在实现里分别抛异常或把 passed 设为 false。
 */
class RagEvaluationRunnerTest {

    /**
     * 构造一条召回和引用都命中、且没有说出禁用表述的观察结果，断言报告通过且 recallAt10 为 1。
     * 断言失败只说明这条黄金样本不再满足门槛，不会代替空列表或非法 JSON 的校验。
     */
    @Test
    void enforcesDeterministicQualityGates() {
        var c = new RagEvaluationCase(
                "rag-1",
                "问题",
                Set.of("000001"),
                Set.of("QUARTERLY_REPORT"),
                Set.of("chunk-1"),
                Set.of("DOC:1"),
                List.of("要点"),
                List.of("保证上涨"),
                false);
        var observed = new RagEvaluationRunner.ObservedCase(
                c, List.of("chunk-1"), Set.of("DOC:1"), List.of("DOC:1"), "合规回答", false);
        var report = new RagEvaluationRunner().run("rag-v1", List.of(observed));
        assertThat(report.passed()).isTrue();
        assertThat(report.scores().get("recallAt10")).isEqualTo(1);
    }
}
