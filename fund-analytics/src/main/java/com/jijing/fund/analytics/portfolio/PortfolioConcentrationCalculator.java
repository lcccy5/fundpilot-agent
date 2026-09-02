package com.jijing.fund.analytics.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public final class PortfolioConcentrationCalculator {
    public Result calculate(List<BigDecimal> positionValues){
        var positive=positionValues==null?List.<BigDecimal>of():positionValues.stream().filter(v->v!=null&&v.signum()>0).sorted(Comparator.reverseOrder()).toList();
        BigDecimal total=positive.stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        if(total.signum()<=0)return new Result(null,null,null,"UNAVAILABLE");
        var weights=positive.stream().map(v->v.divide(total,8,RoundingMode.HALF_UP)).toList();
        BigDecimal max=weights.getFirst();
        BigDecimal top3=weights.stream().limit(3).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal hhi=weights.stream().map(w->w.multiply(w)).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(8,RoundingMode.HALF_UP);
        return new Result(max,top3,hhi,"AVAILABLE");
    }
    public record Result(BigDecimal maxFundWeight,BigDecimal top3Weight,BigDecimal hhi,String status){}
}
