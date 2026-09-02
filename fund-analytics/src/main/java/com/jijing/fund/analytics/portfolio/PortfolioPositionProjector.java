package com.jijing.fund.analytics.portfolio;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.portfolio.*;
import java.math.*;
import java.time.LocalDate;
import java.util.*;

/** 交易流水到持仓的确定性投影；流水而非快照是真实来源。 */
public final class PortfolioPositionProjector {
    private static final int MONEY_SCALE=4;
    public List<FundPosition> project(List<FundTransaction> transactions){
        Map<FundCode,State> states=new TreeMap<>(Comparator.comparing(FundCode::value));
        transactions.stream().sorted(Comparator.comparing(FundTransaction::confirmDate).thenComparing(FundTransaction::transactionId)).forEach(t->apply(states.computeIfAbsent(t.fundCode(),ignored->new State()),t));
        return states.entrySet().stream().map(e->e.getValue().view(e.getKey())).toList();
    }
    private void apply(State s,FundTransaction t){
        switch(t.transactionType()){
            case SUBSCRIPTION,DIVIDEND_REINVESTMENT,CONVERSION_IN -> buy(s,t);
            case REDEMPTION,CONVERSION_OUT -> sell(s,t);
            case CASH_DIVIDEND -> {s.cashDividend=s.cashDividend.add(t.grossAmount().subtract(t.fee()));s.lastDate=t.confirmDate();}
            case FEE_ADJUSTMENT -> {s.remainingCost=s.remainingCost.add(t.fee());s.lastDate=t.confirmDate();}
            case REVERSAL -> throw new IllegalArgumentException("REVERSAL must be resolved into a compensating transaction before projection");
        }
    }
    private void buy(State s,FundTransaction t){
        if(t.shares().signum()<=0)throw new IllegalArgumentException("purchase shares must be positive");
        s.shares=s.shares.add(t.shares());s.remainingCost=s.remainingCost.add(t.grossAmount()).add(t.fee());s.lastDate=t.confirmDate();
    }
    private void sell(State s,FundTransaction t){
        if(t.shares().signum()<=0||s.shares.compareTo(t.shares())<0)throw new IllegalArgumentException("redemption exceeds confirmed shares");
        BigDecimal allocated=s.shares.signum()==0?BigDecimal.ZERO:s.remainingCost.multiply(t.shares()).divide(s.shares,8,RoundingMode.HALF_UP);
        s.shares=s.shares.subtract(t.shares());s.remainingCost=s.remainingCost.subtract(allocated).setScale(MONEY_SCALE,RoundingMode.HALF_UP);
        s.realized=s.realized.add(t.grossAmount().subtract(t.fee()).subtract(allocated));s.lastDate=t.confirmDate();
    }
    private static final class State{
        BigDecimal shares=BigDecimal.ZERO.setScale(8),remainingCost=BigDecimal.ZERO.setScale(MONEY_SCALE),realized=BigDecimal.ZERO.setScale(MONEY_SCALE),cashDividend=BigDecimal.ZERO.setScale(MONEY_SCALE);LocalDate lastDate;
        FundPosition view(FundCode code){return new FundPosition(code,shares,remainingCost,realized,cashDividend,lastDate);}
    }
}
