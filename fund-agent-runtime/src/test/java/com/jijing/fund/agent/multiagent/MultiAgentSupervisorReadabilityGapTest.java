package com.jijing.fund.agent.multiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.agent.exception.AgentPolicyViolationException;
import com.jijing.fund.agent.planning.PlanValidationException;
import com.jijing.fund.agent.planning.PlanValidator;
import com.jijing.fund.agent.planning.RuleBasedPlanner;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 补齐多代理启动失败：路由拒绝权限、计划所有者缺失，以及产物契约拒绝坏的对等代理输出。 监督者追加计划外任务的失败由 {@link MultiAgentAndOutboxTest} 覆盖。 */
class MultiAgentSupervisorReadabilityGapTest {
  private final MultiAgentSupervisor supervisor =
      new MultiAgentSupervisor(
          new ExecutionModeRouter(), new PlanValidator(), new RuleBasedPlanner());

  /** 没有执行权限时路由直接失败，监督者不返回分派。 多代理请求不能把权限拒绝降级成单代理成功。 */
  @Test
  void missingPermissionFailsBeforeAnyPeerStarts() {
    assertThatThrownBy(() -> supervisor.decide("导出我的组合报告", false, true))
        .isInstanceOf(AgentPolicyViolationException.class)
        .hasMessageContaining("permission");
  }

  /** 计划执行已启动但所有者为空白时，校验拒绝整份计划。 不会返回只绑定了部分任务的分派。 */
  @Test
  void blankOwnerRejectsPeerAssignment() {
    assertThatThrownBy(
            () -> supervisor.decide("比较 000001 110022 161725 并结合我的组合生成报告", true, true, " "))
        .isInstanceOf(PlanValidationException.class)
        .hasMessage("owner is required");
  }

  /** 对等代理交出的产物缺少元数据、模式版本或声明时，契约拒绝传递。 撰写者不能靠研究产物绕过该失败。 */
  @Test
  void peerArtifactFailuresStayRejected() {
    ArtifactContract contract = new ArtifactContract();
    assertThatThrownBy(() -> contract.validate(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("artifact metadata is required");
    assertThatThrownBy(
            () -> contract.validate(artifact("v2", AgentRole.DATA_RESEARCHER, "ev-1", "PUBLIC")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported artifact schema");
    assertThatThrownBy(
            () ->
                contract.validate(
                    new StructuredArtifact(
                        "FUND_COMPARE",
                        "v1",
                        AgentRole.DATA_RESEARCHER,
                        "h",
                        Instant.parse("2026-08-27T07:00:00Z"),
                        List.of(),
                        List.of(),
                        "hash")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("claims are required");
    assertThatThrownBy(
            () -> contract.validate(artifact("v1", AgentRole.DATA_RESEARCHER, " ", "PUBLIC")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("claim evidence is required");
  }

  /** 未知能力对任何角色都不可执行，监督者角色也不能执行白名单内的工具。 访问控制拒绝后，调用方不得继续分派。 */
  @Test
  void unknownCapabilityAndSupervisorRoleAreDenied() {
    assertThat(RoleToolAcl.allowed(AgentRole.DATA_RESEARCHER, "NOT_A_CAPABILITY")).isFalse();
    assertThat(RoleToolAcl.allowed(AgentRole.DATA_RESEARCHER, null)).isFalse();
    assertThat(RoleToolAcl.allowed(AgentRole.SUPERVISOR, "FUND_PROFILE_QUERY")).isFalse();
  }

  /** 组装一份只变化模式版本、角色、证据和范围的产物。 其余字段保持完整，便于单独观察契约拒绝的原因。 */
  private StructuredArtifact artifact(
      String schemaVersion, AgentRole role, String evidenceId, String ownerScope) {
    return new StructuredArtifact(
        "FUND_COMPARE",
        schemaVersion,
        role,
        "h",
        Instant.parse("2026-08-27T07:00:00Z"),
        List.of(new StructuredArtifact.Claim("c1", "public compare", evidenceId, ownerScope)),
        List.of(),
        "hash");
  }
}
