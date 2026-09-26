package com.jijing.fund.application.exception;

/**
 * 表示基金代码、日期区间或对比列表无法执行。
 * 在访问仓储或外部数据源之前抛出，消息沿用触发原因，供接口层映射为客户端错误。
 */
public class InvalidFundQueryException extends RuntimeException {

    /**
     * 用已经面向调用方的说明构造查询错误。
     * 不包装其他异常，说明会原样成为运行时消息。
     *
     * @param message 代码、区间或列表不被接受的原因
     */
    public InvalidFundQueryException(String message) {
        super(message);
    }
}
