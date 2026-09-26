package com.jijing.fund.agent.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.agent.api.AgentTaskView;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerificationFailureReadabilityGapTest {
    @Test
    void verifierReportsIncompleteWorkAndDoesNotTreatMissingCitationAsFailure() {
        var verifier = new RunVerifier();
        var incomplete = verifier.verify(List.of(
                new AgentTaskView("1", "metrics", "FUND_METRICS_QUERY", "FAILED", 1, null),
                new AgentTaskView("2", "cancelled", "FUND_COMPARE", "CANCELLED", 0, "should-skip"),
                new AgentTaskView("3", "waiting", "PORTFOLIO_SNAPSHOT", "WAITING_APPROVAL", 0, null),
                new AgentTaskView("4", "write", "REPORT_WRITE", "PENDING", 0, null)));
        assertThat(incomplete.passed()).isFalse();
        assertThat(incomplete.findings()).containsExactly("incomplete:metrics", "missing-verifier");
        assertThat(incomplete.evidenceIds()).isEmpty();

        var noCitation = verifier.verify(List.of(
                new AgentTaskView("1", "metrics", "FUND_METRICS_QUERY", "SUCCEEDED", 1, null),
                new AgentTaskView("2", "verify", "REPORT_VERIFY", "PENDING", 0, null)));
        assertThat(noCitation.passed()).isTrue();
        assertThat(noCitation.evidenceIds()).isEmpty();
        assertThat(noCitation.findings()).isEmpty();

        var cited = verifier.verify(List.of(
                new AgentTaskView("1", "metrics", "FUND_METRICS_QUERY", "SUCCEEDED", 1, "s3://evidence/metrics"),
                new AgentTaskView("2", "verify", "REPORT_VERIFY", "PENDING", 0, null)));
        assertThat(cited.passed()).isTrue();
        assertThat(cited.evidenceIds()).containsExactly("s3://evidence/metrics");
    }

    @Test
    void writerRefusesUnverifiedReportsAndMentionsMissingEvidenceIds() {
        var writer = new ReportWriter();
        assertThatThrownBy(() -> writer.write(null, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("writer requires a passing verification report");
        assertThatThrownBy(() -> writer.write(new VerificationReport(false, List.of("incomplete:metrics"), List.of()), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("writer requires a passing verification report");

        String emptyEvidence = writer.write(new VerificationReport(true, List.of(), List.of()), List.of(
                new AgentTaskView("1", "metrics-task", "FUND_METRICS_QUERY", "SUCCEEDED", 1, null),
                new AgentTaskView("2", "compare", "FUND_COMPARE", "SUCCEEDED", 1, null),
                new AgentTaskView("3", "book", "PORTFOLIO_SNAPSHOT", "SUCCEEDED", 1, null),
                new AgentTaskView("4", "custom-task", "UNKNOWN", "SUCCEEDED", 1, null),
                new AgentTaskView("5", "verify", "REPORT_VERIFY", "SUCCEEDED", 1, null)));
        assertThat(emptyEvidence).contains("基金指标计算（metrics-task）", "基金比较", "持仓读取", "custom-task", "本次任务未返回可追溯证据编号。");

        String cited = writer.write(new VerificationReport(true, List.of(), List.of("ev-1", "ev-2")), List.of());
        assertThat(cited).contains("基础数据分析", "ev-1、ev-2");
    }

    @Test
    void returnPolicyAllowsOnlyOnePlanReturn() {
        var policy = new VerifierReturnPolicy();
        assertThat(policy.allowReturn(0)).isTrue();
        assertThat(policy.allowReturn(1)).isFalse();
        assertThat(policy.allowReturn(2)).isFalse();
    }
}
