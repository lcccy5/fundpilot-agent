package com.jijing.fund.application.risk;

/**
 * 风险档案不存在、问卷版本不受支持或答案不完整时抛出的异常。这几种原因共用一个类型，只能通过消息区分。
 */
public class RiskProfileException extends RuntimeException {
    /**
     * 用给定说明构造异常。说明为空时消息为空。
     */
    public RiskProfileException(String message) {
        super(message);
    }
}
