package com.jijing.fund.analytics.model;

import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 一次净值区间计算得到的收益、风险和极值。
 * 样本不足时各项指标为不可用，最大回撤区间为空。记录不重新校验观测数、日期顺序或原因码；调用方传入的空字段会原样保留。
 * 百分比用小数保存，0.12 表示 12%。组件访问器由记录生成。
 */
public record FundMetrics(FundCode fundCode, LocalDate requestedStartDate, LocalDate requestedEndDate,
        LocalDate actualStartDate, LocalDate actualEndDate, NavBasis navBasis, int observationCount,
        DataCoverage coverage, MetricValue cumulativeReturn, MetricValue annualizedReturn,
        MetricValue annualizedVolatility, MetricValue maxDrawdown, DrawdownPeriod maxDrawdownPeriod,
        MetricValue sharpeRatio, MetricValue positiveDayRatio, MetricValue bestDailyReturn,
        MetricValue worstDailyReturn, BigDecimal annualRiskFreeRate, String algorithmVersion,
        String dataVersion, Instant calculatedAt) {}
