package com.jijing.fund.analytics.portfolio;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;import java.time.LocalDate;import java.util.*;import org.junit.jupiter.api.Test;

class MoneyWeightedReturnCalculatorTest {
    @Test void matchesCheckedExcelSampleCashFlows()throws Exception{
        try(var in=getClass().getResourceAsStream("/xirr-excel-sample.csv")){
            var lines=new String(in.readAllBytes()).lines().toList();
            var flows=new ArrayList<MoneyWeightedReturnCalculator.CashFlow>();
            for(int i=1;i<lines.size();i++){var p=lines.get(i).split(",");flows.add(new MoneyWeightedReturnCalculator.CashFlow(LocalDate.parse(p[0]),new BigDecimal(p[1])));}
            assertThat(new MoneyWeightedReturnCalculator().calculate(flows)).hasValueSatisfying(v->assertThat(v).isCloseTo(new BigDecimal("0.10"),within(new BigDecimal("0.001"))));
        }
    }
    @Test void returnsEmptyWhenCashFlowsCannotHaveIrr(){assertThat(new MoneyWeightedReturnCalculator().calculate(List.of(new MoneyWeightedReturnCalculator.CashFlow(LocalDate.now(),BigDecimal.ONE),new MoneyWeightedReturnCalculator.CashFlow(LocalDate.now().plusDays(1),BigDecimal.TEN)))).isEmpty();}
}
