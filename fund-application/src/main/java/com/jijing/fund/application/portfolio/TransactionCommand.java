package com.jijing.fund.application.portfolio;

import com.jijing.fund.domain.portfolio.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 写入组合前的一笔记账命令。构造时不校验；缺类型、日期或幂等键、确认日早于交易日、冲正没有原流水号，都会在用例写入时被拒绝。
 * 基金代码不是六位数字时，要到创建流水才会抛出参数异常。份额和金额的负数由流水值对象拒绝。
 */
public record TransactionCommand(String fundCode, TransactionType type, LocalDate tradeDate, LocalDate confirmDate, BigDecimal shares,
        BigDecimal grossAmount, BigDecimal fee, BigDecimal confirmedNav, String idempotencyKey, String note, String reversesTransactionId) {
    /**
     * 构造一笔不引用原流水的命令，冲正流水号固定为空。字段仍然留到写入时校验，这里接受空值和非法代码。
     */
    public TransactionCommand(String fundCode, TransactionType type, LocalDate tradeDate, LocalDate confirmDate, BigDecimal shares,
            BigDecimal grossAmount, BigDecimal fee, BigDecimal confirmedNav, String idempotencyKey, String note) {
        this(fundCode, type, tradeDate, confirmDate, shares, grossAmount, fee, confirmedNav, idempotencyKey, note, null);
    }
}
