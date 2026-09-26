package com.jijing.fund.application.dto;

import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.model.NavBasis;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 多只基金在共同重叠区间上的对比结果。
 * 基金数量不合法、没有重叠区间或净值口径混用时不会返回本结果。
 *
 * @param commonStartDate 各基金实际起点中的最晚日期
 * @param commonEndDate 各基金实际终点中的最早日期
 * @param navBasis 对齐后实际使用的净值口径
 * @param funds 在重叠区间上重算得到的指标
 * @param rankings 按指标名称分组的排名，覆盖累计收益、年化波动、最大回撤和夏普比率
 */
public record FundComparisonResult(LocalDate commonStartDate, LocalDate commonEndDate, NavBasis navBasis,
        List<FundMetrics> funds, Map<String, List<MetricRanking>> rankings) {
}
