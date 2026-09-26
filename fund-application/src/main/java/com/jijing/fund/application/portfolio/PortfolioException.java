package com.jijing.fund.application.portfolio;

/**
 * 组合用例拒绝当前操作时抛出的异常。找不到和冲突是它的子类，只捕获本类型时也会接住那两种情况。
 * 消息面向调用方，不包含堆栈以外的内部状态；空消息会被原样保存。
 */
public class PortfolioException extends RuntimeException {
    /**
     * 用给定说明构造异常。说明为空时异常消息也为空，不会再补一句默认原因。
     */
    public PortfolioException(String message) {
        super(message);
    }
}
