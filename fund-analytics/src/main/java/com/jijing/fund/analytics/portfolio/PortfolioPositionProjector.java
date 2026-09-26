package com.jijing.fund.analytics.portfolio;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.portfolio.FundPosition;
import com.jijing.fund.domain.portfolio.FundTransaction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 按确认日和流水号重放交易，得到每只基金的已确认份额、剩余成本、已实现收益和累计现金分红。
 * 空流水得到空列表；同一批流水重复重放，持仓数值与顺序保持一致。买入份额不大于零、赎回份额不大于零或超过已确认份额时抛出
 * {@link IllegalArgumentException}；冲正类型必须先拆成补偿流水，否则同样抛出该异常且不产生持仓。入参为 null，或列表中含有 null 流水时，抛出
 * {@link NullPointerException}。
 */
public final class PortfolioPositionProjector {
    private static final int MONEY_SCALE = 4;

    /**
     * 把流水归集为按基金代码排序的持仓。
     * 空列表返回空列表。null 列表或 null 元素在排序或读取基金代码时抛出 {@link NullPointerException}。
     * 非法份额、超额赎回或未拆分的冲正在对应流水处中断，调用方拿不到部分结果。相同输入多次调用结果相等。
     */
    public List<FundPosition> project(List<FundTransaction> transactions) {
        Map<FundCode, State> states = new TreeMap<>(Comparator.comparing(FundCode::value));
        transactions.stream()
                .sorted(Comparator.comparing(FundTransaction::confirmDate).thenComparing(FundTransaction::transactionId))
                .forEach(transaction -> apply(states.computeIfAbsent(transaction.fundCode(), ignored -> new State()), transaction));
        return states.entrySet().stream().map(entry -> entry.getValue().view(entry.getKey())).toList();
    }

    /**
     * 把一笔流水记入该基金的累计状态。
     * 申购、红利再投资和转换转入按买入处理；赎回和转换转出按卖出处理。现金分红只累加净额并刷新确认日；费用调整把费用加进剩余成本。
     * 冲正直接抛出 {@link IllegalArgumentException}，不改动已有累计值。
     */
    private void apply(State state, FundTransaction transaction) {
        switch (transaction.transactionType()) {
            case SUBSCRIPTION, DIVIDEND_REINVESTMENT, CONVERSION_IN -> buy(state, transaction);
            case REDEMPTION, CONVERSION_OUT -> sell(state, transaction);
            case CASH_DIVIDEND -> {
                state.cashDividend = state.cashDividend.add(transaction.grossAmount().subtract(transaction.fee()));
                state.lastDate = transaction.confirmDate();
            }
            case FEE_ADJUSTMENT -> {
                state.remainingCost = state.remainingCost.add(transaction.fee());
                state.lastDate = transaction.confirmDate();
            }
            case REVERSAL -> throw new IllegalArgumentException(
                    "REVERSAL must be resolved into a compensating transaction before projection");
        }
    }

    /**
     * 增加份额，并把成交额与费用一并计入剩余成本。
     * 份额小于等于零时抛出 {@link IllegalArgumentException}，本次不改状态。
     */
    private void buy(State state, FundTransaction transaction) {
        if (transaction.shares().signum() <= 0) {
            throw new IllegalArgumentException("purchase shares must be positive");
        }
        state.shares = state.shares.add(transaction.shares());
        state.remainingCost = state.remainingCost.add(transaction.grossAmount()).add(transaction.fee());
        state.lastDate = transaction.confirmDate();
    }

    /**
     * 按卖出份额占当前份额的比例结转成本，并把成交净额与结转成本的差额记为已实现收益。
     * 卖出份额小于等于零，或大于当前已确认份额时抛出 {@link IllegalArgumentException}，状态保持调用前的值。
     * 当前份额已为零时结转额按零处理；份额校验通过后该分支不会被走到。
     */
    private void sell(State state, FundTransaction transaction) {
        if (transaction.shares().signum() <= 0 || state.shares.compareTo(transaction.shares()) < 0) {
            throw new IllegalArgumentException("redemption exceeds confirmed shares");
        }
        BigDecimal allocated = state.shares.signum() == 0
                ? BigDecimal.ZERO
                : state.remainingCost.multiply(transaction.shares()).divide(state.shares, 8, RoundingMode.HALF_UP);
        state.shares = state.shares.subtract(transaction.shares());
        state.remainingCost = state.remainingCost.subtract(allocated).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        state.realized = state.realized.add(transaction.grossAmount().subtract(transaction.fee()).subtract(allocated));
        state.lastDate = transaction.confirmDate();
    }

    /**
     * 保存单只基金重放过程中的可变累计值。
     * 份额从 8 位小数的零开始，金额从 4 位小数的零开始，最近确认日在首笔流水之前为空。字段本身不拒绝非法组合。
     */
    private static final class State {
        private BigDecimal shares = BigDecimal.ZERO.setScale(8);
        private BigDecimal remainingCost = BigDecimal.ZERO.setScale(MONEY_SCALE);
        private BigDecimal realized = BigDecimal.ZERO.setScale(MONEY_SCALE);
        private BigDecimal cashDividend = BigDecimal.ZERO.setScale(MONEY_SCALE);
        private LocalDate lastDate;

        /**
         * 用当前累计值生成一份不可变持仓。
         * 最近确认日仍为空时原样写入；零份额也不会在这里被拒绝。
         */
        private FundPosition view(FundCode code) {
            return new FundPosition(code, shares, remainingCost, realized, cashDividend, lastDate);
        }
    }
}
