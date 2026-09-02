package com.jijing.fund.agent.planning;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanValidatorTest {
    private final PlanValidator validator=new PlanValidator();
    @Test void rejectsUnknownTaskAndCycles(){
        var unknown=new PlanDraft("g",Map.of(),Map.of("maxTasks",2),List.of(new PlanTaskDraft("a","SQL_INJECTION",Map.of(),List.of(),List.of("X"))));
        assertThatThrownBy(()->validator.validate(unknown,"user")).isInstanceOf(PlanValidationException.class);
        var cyclic=new PlanDraft("g",Map.of(),Map.of(),List.of(
                new PlanTaskDraft("a","FUND_PROFILE_QUERY",Map.of("fundCode","000001"),List.of("b"),List.of("FUND_PROFILE")),
                new PlanTaskDraft("b","FUND_NAV_QUERY",Map.of("fundCode","000001"),List.of("a"),List.of("FUND_NAV"))));
        assertThatThrownBy(()->validator.validate(cyclic,"user")).isInstanceOf(PlanValidationException.class);
    }
    @Test void rejectsOverBudgetMissingEvidenceAndCrossUserInput(){
        var over=new PlanDraft("g",Map.of(),Map.of("maxTasks",1),List.of(
                new PlanTaskDraft("a","FUND_PROFILE_QUERY",Map.of("fundCode","000001"),List.of(),List.of("FUND_PROFILE")),
                new PlanTaskDraft("b","FUND_NAV_QUERY",Map.of("fundCode","000001"),List.of("a"),List.of("FUND_NAV"))));
        assertThatThrownBy(()->validator.validate(over,"user")).isInstanceOf(PlanValidationException.class);
        var noEvidence=new PlanDraft("g",Map.of(),Map.of(),List.of(new PlanTaskDraft("a","FUND_PROFILE_QUERY",Map.of("fundCode","000001"),List.of(),List.of())));
        assertThatThrownBy(()->validator.validate(noEvidence,"user")).isInstanceOf(PlanValidationException.class);
        var leak=new PlanDraft("g",Map.of(),Map.of(),List.of(new PlanTaskDraft("a","PORTFOLIO_SNAPSHOT",Map.of("userId","other"),List.of(),List.of("PORTFOLIO"))));
        assertThatThrownBy(()->validator.validate(leak,"user")).isInstanceOf(PlanValidationException.class);
        var echoedOwner=new PlanDraft("g",Map.of(),Map.of(),List.of(new PlanTaskDraft("a","PORTFOLIO_SNAPSHOT",Map.of("userId","user"),List.of(),List.of("PORTFOLIO"))));
        assertThatThrownBy(()->validator.validate(echoedOwner,"user")).isInstanceOf(PlanValidationException.class);
    }
    @Test void acceptsAcyclicWhitelistedPlan(){
        validator.validate(new PlanDraft("compare",Map.of("userIdHash","x"),Map.of("maxTasks",4),List.of(
                new PlanTaskDraft("p","FUND_PROFILE_QUERY",Map.of("fundCode","000001"),List.of(),List.of("FUND_PROFILE")),
                new PlanTaskDraft("m","FUND_METRICS_QUERY",Map.of("fundCode","000001"),List.of("p"),List.of("FUND_METRICS"))
        )),"user-1");
    }
}
