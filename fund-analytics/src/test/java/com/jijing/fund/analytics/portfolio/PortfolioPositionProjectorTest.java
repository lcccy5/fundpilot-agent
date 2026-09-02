package com.jijing.fund.analytics.portfolio;

import static org.assertj.core.api.Assertions.*;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.portfolio.*;
import java.math.BigDecimal;import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;

class PortfolioPositionProjectorTest {
    @Test void replaysSubscriptionAndRedemptionWithMovingAverageCost(){
        var p=new PortfolioId("00000000-0000-0000-0000-000000000100");var u=new UserId("00000000-0000-0000-0000-000000000200");var f=new FundCode("000001");var projector=new PortfolioPositionProjector();
        var rows=List.of(tx("a",p,u,f,TransactionType.SUBSCRIPTION,"10","100","1",LocalDate.of(2026,1,2)),tx("b",p,u,f,TransactionType.REDEMPTION,"4","50","1",LocalDate.of(2026,1,3)));
        var result=projector.project(rows).getFirst();
        assertThat(result.confirmedShares()).isEqualByComparingTo("6");assertThat(result.remainingCost()).isEqualByComparingTo("60.6000");assertThat(result.realizedProfit()).isEqualByComparingTo("8.6000");
        assertThat(projector.project(rows)).isEqualTo(projector.project(rows));
    }
    @Test void compensatingRedemptionUndoesSubscription(){
        var p=new PortfolioId("00000000-0000-0000-0000-000000000102");var u=new UserId("00000000-0000-0000-0000-000000000202");var f=new FundCode("000001");var projector=new PortfolioPositionProjector();
        var buy=tx("a",p,u,f,TransactionType.SUBSCRIPTION,"10","100","0",LocalDate.of(2026,1,2));
        var undo=tx("b",p,u,f,TransactionType.REDEMPTION,"10","100","0",LocalDate.of(2026,1,3));
        var after=projector.project(List.of(buy,undo)).getFirst();
        assertThat(after.confirmedShares()).isEqualByComparingTo("0");
        assertThat(after.remainingCost()).isEqualByComparingTo("0.0000");
    }
    @Test void rejectsRedemptionBeyondConfirmedShares(){
        var p=new PortfolioId("00000000-0000-0000-0000-000000000101");var u=new UserId("00000000-0000-0000-0000-000000000201");var f=new FundCode("000001");
        assertThatThrownBy(()->new PortfolioPositionProjector().project(List.of(tx("a",p,u,f,TransactionType.REDEMPTION,"1","1","0",LocalDate.now())))).isInstanceOf(IllegalArgumentException.class);
    }
    private static FundTransaction tx(String id,PortfolioId p,UserId u,FundCode f,TransactionType type,String shares,String amount,String fee,LocalDate date){return new FundTransaction(id,p,u,f,type,date,date,new BigDecimal(shares),new BigDecimal(amount),new BigDecimal(fee),null,"CNY","TEST",id,null,Instant.EPOCH);}
}
