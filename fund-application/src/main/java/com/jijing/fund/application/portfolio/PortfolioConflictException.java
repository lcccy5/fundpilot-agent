package com.jijing.fund.application.portfolio;

/**
 * 组合或导入批次的状态与这次操作冲突，例如版本已被推进、批次不是预览态、文件摘要不一致或流水已经冲正。
 * 它是组合异常的一种，调用方若要和普通校验失败区分，必须按具体类型捕获。
 */
public class PortfolioConflictException extends PortfolioException {
    /**
     * 用给定说明构造冲突异常。说明为空时消息为空。
     */
    public PortfolioConflictException(String message) {
        super(message);
    }
}
