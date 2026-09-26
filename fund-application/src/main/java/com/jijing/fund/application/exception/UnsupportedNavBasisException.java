package com.jijing.fund.application.exception;

/**
 * 表示请求的净值口径无法用于本次计算或对比。
 * 名称无法识别、累计净值不完整，或对比中的基金口径不一致时抛出；调整净值缺失时指标查询会改用单位净值，不抛出本异常。
 */
public class UnsupportedNavBasisException extends RuntimeException {

    /**
     * 用已经面向调用方的说明构造口径错误。
     * 说明会原样成为运行时消息，本类型不附加错误码。
     *
     * @param message 口径不被接受的原因
     */
    public UnsupportedNavBasisException(String message) {
        super(message);
    }
}
