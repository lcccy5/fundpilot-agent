package com.jijing.fund.application.exception;

/**
 * 表示请求区间内没有可用于计算的确认或修正净值。
 * 本地与上游都没有合格样本时由指标查询抛出，不返回空指标冒充计算结果。
 */
public class NavDataNotReadyException extends RuntimeException {

    /**
     * 用基金代码说明哪一只基金的净值尚未就绪。
     * 代码会写入固定消息，本类型不再校验代码格式。
     *
     * @param code 已解析的基金代码文本
     */
    public NavDataNotReadyException(String code) {
        super("NAV data is not ready for fund: " + code);
    }
}
