package com.jijing.fund.application.exception;

/**
 * 表示本地和外部数据源都没有请求的基金，或净值历史装载后仓储中仍没有档案。
 * 不返回空档案冒充成功。
 */
public class FundNotFoundException extends RuntimeException {

    /**
     * 用调用方或已解析的基金代码说明哪一只基金不存在。
     * 代码会写入固定消息；净值历史路径使用调用方原文，其余路径使用解析后的代码。
     *
     * @param fundCode 未能找到的基金代码文本
     */
    public FundNotFoundException(String fundCode) {
        super("Fund not found: " + fundCode);
    }
}
