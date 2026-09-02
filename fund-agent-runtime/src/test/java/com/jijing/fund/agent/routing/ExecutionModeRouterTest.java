package com.jijing.fund.agent.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.jijing.fund.agent.exception.AgentPolicyViolationException;
import org.junit.jupiter.api.Test;

class ExecutionModeRouterTest {
    private final ExecutionModeRouter router=new ExecutionModeRouter();
    @Test void simpleConceptStaysDirect(){
        var d=router.route("最大回撤是什么意思？",true);
        assertThat(d.mode()).isEqualTo(ExecutionMode.DIRECT);
        assertThat(d.directVariant()).isEqualTo(DirectVariant.NO_TOOL);
    }
    @Test void singleFundQueryUsesDeterministicTool(){
        assertThat(router.route("查询 000001 最新资料",true).mode()).isEqualTo(ExecutionMode.DETERMINISTIC_TOOL);
    }
    @Test void explorationUsesBoundedReact(){
        assertThat(router.route("000001 最近为什么下跌？",true).mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
    }
    @Test void multiFundReportUsesPlan(){
        assertThat(router.route("比较 000001 110022 161725 并结合我的组合生成报告",true).mode()).isEqualTo(ExecutionMode.PLAN_AND_EXECUTE);
    }
    @Test void userCannotForceExpensiveMode(){
        assertThat(router.route("请使用最复杂模式回答：什么是净值？",true).mode()).isNotEqualTo(ExecutionMode.PLAN_AND_EXECUTE);
    }
    @Test void deniedPermissionDoesNotPlan(){
        assertThatThrownBy(()->router.route("导出我的组合报告",false))
                .isInstanceOf(AgentPolicyViolationException.class);
    }
}
