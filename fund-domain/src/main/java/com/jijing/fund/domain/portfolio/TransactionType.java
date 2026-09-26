package com.jijing.fund.domain.portfolio;

/**
 * 基金交易类型，按名称持久化：申购、赎回、现金分红、红利再投资、转换转出、转换转入、冲正和费用调整。
 * 冲正（REVERSAL）本身不能再被冲正，该规则由应用层执行。未知名称在 {@code valueOf} 时抛出 IllegalArgumentException。
 */
public enum TransactionType { SUBSCRIPTION, REDEMPTION, CASH_DIVIDEND, DIVIDEND_REINVESTMENT, CONVERSION_OUT, CONVERSION_IN, REVERSAL, FEE_ADJUSTMENT }
