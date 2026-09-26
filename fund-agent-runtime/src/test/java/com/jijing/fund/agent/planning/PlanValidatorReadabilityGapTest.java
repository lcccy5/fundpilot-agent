package com.jijing.fund.agent.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 补齐空计划被拒绝，以及重规划额度用尽后不得再生成替代计划。 未知任务类型、环和越权输入由 {@link PlanValidatorTest} 覆盖。 */
class PlanValidatorReadabilityGapTest {
  private final PlanValidator validator = new PlanValidator();

  /** 草稿本身缺失时拒绝执行。 不会把 null 当成零任务的可运行计划。 */
  @Test
  void nullDraftIsRejected() {
    assertThatThrownBy(() -> validator.validate(null, "user"))
        .isInstanceOf(PlanValidationException.class)
        .hasMessage("goal is required");
  }

  /** 目标为空白时拒绝计划。 任务列表即使存在也不能单独放行。 */
  @Test
  void blankGoalIsRejected() {
    var draft = new PlanDraft("  ", Map.of(), Map.of(), List.of(task("a")));
    assertThatThrownBy(() -> validator.validate(draft, "user"))
        .isInstanceOf(PlanValidationException.class)
        .hasMessage("goal is required");
  }

  /** 任务列表为空时拒绝计划。 调用方不得提交一个没有步骤的执行图。 */
  @Test
  void emptyTaskListIsRejected() {
    var draft = new PlanDraft("compare", Map.of(), Map.of(), List.of());
    assertThatThrownBy(() -> validator.validate(draft, "user"))
        .isInstanceOf(PlanValidationException.class)
        .hasMessage("tasks are required");
  }

  /** 任务列表为 null 时与空列表一样拒绝。 不会在校验器内部补上默认任务。 */
  @Test
  void nullTaskListIsRejected() {
    var draft = new PlanDraft("compare", Map.of(), Map.of(), null);
    assertThatThrownBy(() -> validator.validate(draft, "user"))
        .isInstanceOf(PlanValidationException.class)
        .hasMessage("tasks are required");
  }

  /** 所有者缺失时拒绝计划，即使任务本身合法。 身份不能从任务输入里回填。 */
  @Test
  void blankOwnerRejectsOtherwiseValidPlan() {
    var draft = new PlanDraft("compare", Map.of(), Map.of(), List.of(task("a")));
    assertThatThrownBy(() -> validator.validate(draft, " "))
        .isInstanceOf(PlanValidationException.class)
        .hasMessage("owner is required");
  }

  /** 已用完重规划次数时不再允许新草稿。 负数上限会被当成零，第一次请求也被拒绝。 */
  @Test
  void exhaustedReplanBudgetRejectsAnotherPlan() {
    assertThat(new ReplanPolicy(1).allow(1)).isFalse();
    assertThat(new ReplanPolicy(-1).allow(0)).isFalse();
  }

  /** 构造一条可通过类型和证据检查的任务，便于单独观察目标、空列表或所有者失败。 任务本身不代表计划已经放行。 */
  private PlanTaskDraft task(String key) {
    return new PlanTaskDraft(
        key,
        "FUND_PROFILE_QUERY",
        Map.of("fundCode", "000001"),
        List.of(),
        List.of("FUND_PROFILE"));
  }
}
