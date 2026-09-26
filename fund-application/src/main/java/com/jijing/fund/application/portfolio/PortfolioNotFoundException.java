package com.jijing.fund.application.portfolio;

/**
 * 当前用户名下不存在请求的组合、流水或导入批次。其他用户的资源也表现为找不到，避免泄露资源是否存在。
 */
public class PortfolioNotFoundException extends PortfolioException {
    /**
     * 用给定说明构造找不到异常。说明为空时消息为空。
     */
    public PortfolioNotFoundException(String message) {
        super(message);
    }
}
