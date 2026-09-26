package com.jijing.fund.application.exception;

/**
 * 表示参与对比的基金没有共同的净值区间。
 * 实际起止日期全为空，或最晚起点晚于最早终点时抛出，不会返回空排名。
 */
public class NoOverlappingPeriodException extends RuntimeException {

    /**
     * 用固定说明构造无重叠区间错误。
     * 不接收调用方消息，避免不同入口写出不一致的原因。
     */
    public NoOverlappingPeriodException() {
        super("Funds have no overlapping NAV period");
    }
}
