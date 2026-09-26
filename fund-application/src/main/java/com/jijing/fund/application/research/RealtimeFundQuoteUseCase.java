package com.jijing.fund.application.research;

/**
 * 查询单只基金的场内代理实时行情。本用例没有用户参数，结果不按账户隔离。
 * 没有代理标的或行情暂时缺失时应返回明确状态，而不是把缺失伪装成价格。
 */
public interface RealtimeFundQuoteUseCase {
    /**
     * 查询基金代码对应的代理行情。代码不是六位数字或为空时失败。没有关联 ETF 或供应商没有报价时返回不可用状态，不抛业务异常。
     * 供应商自身抛出的异常会原样向外传播。
     */
    RealtimeFundQuoteResult query(String fundCode);
}
