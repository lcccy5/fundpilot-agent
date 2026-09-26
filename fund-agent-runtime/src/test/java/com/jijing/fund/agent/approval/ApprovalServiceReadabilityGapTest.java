package com.jijing.fund.agent.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 补齐审批拒绝路径：过期、缺少过期时间、缺少当前时间。 参数被改写或审批已使用的拒绝由 {@link ApprovalServiceTest} 覆盖。 */
class ApprovalServiceReadabilityGapTest {
  private final ApprovalService service = new ApprovalService();
  private final Instant now = Instant.parse("2026-08-27T08:00:00Z");

  /** 当前时间晚于过期时间时审批失效。 调用方必须把该结果当作拒绝，不能继续执行导出或发布。 */
  @Test
  void expiredApprovalIsRejected() {
    String hash = service.hash("export:v1");
    assertThat(service.isValid(hash, "export:v1", now.minusSeconds(1), now, null)).isFalse();
  }

  /** 没有过期时间的审批不能放行。 缺失时间不按“永不过期”处理。 */
  @Test
  void missingExpiryRejectsApproval() {
    String hash = service.hash("export:v1");
    assertThat(service.isValid(hash, "export:v1", null, now, null)).isFalse();
  }

  /** 没有当前时间时无法证明审批仍在有效期内。 该缺口同样视为拒绝。 */
  @Test
  void missingCurrentTimeRejectsApproval() {
    String hash = service.hash("export:v1");
    assertThat(service.isValid(hash, "export:v1", now.plusSeconds(60), null, null)).isFalse();
  }
}
