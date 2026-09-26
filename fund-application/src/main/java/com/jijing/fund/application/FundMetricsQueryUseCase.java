package com.jijing.fund.application;

import com.jijing.fund.analytics.model.FundMetrics;
import java.time.LocalDate;

/**
 * 按请求区间和净值口径计算单只基金的收益风险指标。
 * 本地没有确认净值时会尝试回源；仍然没有可计算样本、代码或口径无法接受时失败。
 */
public interface FundMetricsQueryUseCase {

    /**
     * 组装净值序列并计算指标，命中缓存时直接返回已有结果。
     * 代码非法或区间缺失、颠倒时抛出无效查询；本地和上游都没有档案时抛出基金不存在；过滤后仍没有确认或修正净值时抛出净值未就绪；请求的口径无法识别或累计净值不完整时抛出不支持的净值口径。
     * 调整净值不完整时不失败，改用单位净值继续计算，实际口径体现在返回结果中。
     *
     * @param fundCode 调用方传入的基金代码文本
     * @param startDate 区间起点，含当日
     * @param endDate 区间终点，含当日
     * @param navBasis 请求的净值口径名称；空白表示由序列完整程度自动选择
     * @return 本次计算或缓存中的指标
     */
    FundMetrics calculate(String fundCode, LocalDate startDate, LocalDate endDate, String navBasis);
}
