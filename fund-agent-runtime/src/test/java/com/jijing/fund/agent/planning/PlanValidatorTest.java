package com.jijing.fund.agent.planning;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 确认未知任务、依赖环、超预算、缺证据和越权输入都会拒绝整份计划。 空计划由 {@link PlanValidatorReadabilityGapTest} 覆盖。 */
class PlanValidatorTest {
  private final PlanValidator validator = new PlanValidator();

  /** 白名单外的任务类型和互相依赖的环都使计划失败。 失败后不能执行其中已经看起来合法的任务。 */
  @Test
  void rejectsUnknownTaskAndCycles() {
    var unknown =
        new PlanDraft(
            "g",
            Map.of(),
            Map.of("maxTasks", 2),
            List.of(new PlanTaskDraft("a", "SQL_INJECTION", Map.of(), List.of(), List.of("X"))));
    assertThatThrownBy(() -> validator.validate(unknown, "user"))
        .isInstanceOf(PlanValidationException.class);
    var cyclic =
        new PlanDraft(
            "g",
            Map.of(),
            Map.of(),
            List.of(
                new PlanTaskDraft(
                    "a",
                    "FUND_PROFILE_QUERY",
                    Map.of("fundCode", "000001"),
                    List.of("b"),
                    List.of("FUND_PROFILE")),
                new PlanTaskDraft(
                    "b",
                    "FUND_NAV_QUERY",
                    Map.of("fundCode", "000001"),
                    List.of("a"),
                    List.of("FUND_NAV"))));
    assertThatThrownBy(() -> validator.validate(cyclic, "user"))
        .isInstanceOf(PlanValidationException.class);
  }

  /** 超出任务预算、没有证据要求，或输入携带用户标识时拒绝计划。 即使回显的是当前所有者，也视为可被篡改的身份，不能放行。 */
  @Test
  void rejectsOverBudgetMissingEvidenceAndCrossUserInput() {
    var over =
        new PlanDraft(
            "g",
            Map.of(),
            Map.of("maxTasks", 1),
            List.of(
                new PlanTaskDraft(
                    "a",
                    "FUND_PROFILE_QUERY",
                    Map.of("fundCode", "000001"),
                    List.of(),
                    List.of("FUND_PROFILE")),
                new PlanTaskDraft(
                    "b",
                    "FUND_NAV_QUERY",
                    Map.of("fundCode", "000001"),
                    List.of("a"),
                    List.of("FUND_NAV"))));
    assertThatThrownBy(() -> validator.validate(over, "user"))
        .isInstanceOf(PlanValidationException.class);
    var noEvidence =
        new PlanDraft(
            "g",
            Map.of(),
            Map.of(),
            List.of(
                new PlanTaskDraft(
                    "a",
                    "FUND_PROFILE_QUERY",
                    Map.of("fundCode", "000001"),
                    List.of(),
                    List.of())));
    assertThatThrownBy(() -> validator.validate(noEvidence, "user"))
        .isInstanceOf(PlanValidationException.class);
    var leak =
        new PlanDraft(
            "g",
            Map.of(),
            Map.of(),
            List.of(
                new PlanTaskDraft(
                    "a",
                    "PORTFOLIO_SNAPSHOT",
                    Map.of("userId", "other"),
                    List.of(),
                    List.of("PORTFOLIO"))));
    assertThatThrownBy(() -> validator.validate(leak, "user"))
        .isInstanceOf(PlanValidationException.class);
    var echoedOwner =
        new PlanDraft(
            "g",
            Map.of(),
            Map.of(),
            List.of(
                new PlanTaskDraft(
                    "a",
                    "PORTFOLIO_SNAPSHOT",
                    Map.of("userId", "user"),
                    List.of(),
                    List.of("PORTFOLIO"))));
    assertThatThrownBy(() -> validator.validate(echoedOwner, "user"))
        .isInstanceOf(PlanValidationException.class);
  }

  /** 无环且类型在白名单内的计划可以通过校验。 通过并不表示审批已同意或对等代理已经执行。 */
  @Test
  void acceptsAcyclicWhitelistedPlan() {
    validator.validate(
        new PlanDraft(
            "compare",
            Map.of("userIdHash", "x"),
            Map.of("maxTasks", 4),
            List.of(
                new PlanTaskDraft(
                    "p",
                    "FUND_PROFILE_QUERY",
                    Map.of("fundCode", "000001"),
                    List.of(),
                    List.of("FUND_PROFILE")),
                new PlanTaskDraft(
                    "m",
                    "FUND_METRICS_QUERY",
                    Map.of("fundCode", "000001"),
                    List.of("p"),
                    List.of("FUND_METRICS")))),
        "user-1");
  }
}
