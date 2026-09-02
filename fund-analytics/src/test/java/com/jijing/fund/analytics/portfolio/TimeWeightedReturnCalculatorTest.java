package com.jijing.fund.analytics.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimeWeightedReturnCalculatorTest {
    @Test void returnsEmptyWhenBeginValueMissingInsteadOfZero(){
        var calc=new TimeWeightedReturnCalculator();
        assertThat(calc.calculate(List.of(new TimeWeightedReturnCalculator.SubPeriod(LocalDate.MIN,LocalDate.MAX,null,BigDecimal.TEN,BigDecimal.ZERO)))).isEmpty();
    }
    @Test void chainsPositiveGrowth(){
        var calc=new TimeWeightedReturnCalculator();
        var result=calc.calculate(List.of(new TimeWeightedReturnCalculator.SubPeriod(LocalDate.of(2026,1,1),LocalDate.of(2026,6,1),new BigDecimal("100"),new BigDecimal("110"),BigDecimal.ZERO)));
        assertThat(result).contains(new BigDecimal("0.10000000"));
    }
}
