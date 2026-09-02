package com.jijing.fund.analytics.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/** Chains sub-period returns between cash-flow dates. Missing values return empty, never 0. */
public final class TimeWeightedReturnCalculator {
    public Optional<BigDecimal> calculate(List<SubPeriod> periods){
        if(periods==null||periods.isEmpty())return Optional.empty();
        BigDecimal product=BigDecimal.ONE;
        for(SubPeriod period:periods){
            if(period.beginValue()==null||period.endValue()==null||period.beginValue().signum()<=0)return Optional.empty();
            BigDecimal growth=period.endValue().subtract(period.externalFlow()).divide(period.beginValue(),12,RoundingMode.HALF_UP);
            if(growth.signum()<=0)return Optional.empty();
            product=product.multiply(growth);
        }
        return Optional.of(product.subtract(BigDecimal.ONE).setScale(8,RoundingMode.HALF_UP));
    }
    public record SubPeriod(LocalDate start,LocalDate end,BigDecimal beginValue,BigDecimal endValue,BigDecimal externalFlow){
        public SubPeriod{Objects.requireNonNull(start);Objects.requireNonNull(end);externalFlow=externalFlow==null?BigDecimal.ZERO:externalFlow;}
    }
}
