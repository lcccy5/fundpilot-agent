package com.jijing.fund.agent.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 确认参数被改写或审批已使用后不能继续放行。 过期和缺少时间的拒绝由 {@link ApprovalServiceReadabilityGapTest} 覆盖。 */
class ApprovalServiceTest {

  /** 摘要一致且未使用时放行；参数变化或已经使用时拒绝。 拒绝结果不得被调用方当成仍可导出。 */
  @Test
  void parameterChangeInvalidatesApproval() {
    var service = new ApprovalService();
    String hash = service.hash("export:v1");
    Instant now = Instant.parse("2026-08-27T08:00:00Z");
    assertThat(service.isValid(hash, "export:v1", now.plusSeconds(60), now, null)).isTrue();
    assertThat(service.isValid(hash, "export:v2", now.plusSeconds(60), now, null)).isFalse();
    assertThat(service.isValid(hash, "export:v1", now.plusSeconds(60), now, now)).isFalse();
  }
}
