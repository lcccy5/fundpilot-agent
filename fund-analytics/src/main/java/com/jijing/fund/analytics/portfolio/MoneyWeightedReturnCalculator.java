package com.jijing.fund.analytics.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** XIRR 使用固定区间二分，无法证明有解时明确返回空。 */
public final class MoneyWeightedReturnCalculator {
    public Optional<BigDecimal> calculate(List<CashFlow> flows){
        if(flows==null||flows.size()<2)return Optional.empty();
        var ordered=flows.stream().sorted(Comparator.comparing(CashFlow::date)).toList();
        if(ordered.stream().noneMatch(f->f.amount().signum()>0)||ordered.stream().noneMatch(f->f.amount().signum()<0))return Optional.empty();
        double low=-.9999,high=100d;double a=npv(ordered,low),b=npv(ordered,high);
        if(!Double.isFinite(a)||!Double.isFinite(b)||a*b>0)return Optional.empty();
        for(int i=0;i<200;i++){double mid=(low+high)/2d,v=npv(ordered,mid);if(!Double.isFinite(v))return Optional.empty();if(Math.abs(v)<1e-8)return Optional.of(BigDecimal.valueOf(mid));if(a*v<=0){high=mid;b=v;}else{low=mid;a=v;}}
        return Optional.empty();
    }
    private double npv(List<CashFlow> flows,double rate){LocalDate start=flows.getFirst().date();return flows.stream().mapToDouble(f->f.amount().doubleValue()/StrictMath.pow(1d+rate,ChronoUnit.DAYS.between(start,f.date())/365.2425d)).sum();}
    public record CashFlow(LocalDate date,BigDecimal amount){public CashFlow{Objects.requireNonNull(date);Objects.requireNonNull(amount);}}
}
