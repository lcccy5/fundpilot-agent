package com.jijing.fund.analytics.model;

import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 区分一次基金指标计算所需的基金、区间、净值口径、无风险利率、数据版本和算法版本。
 * 任一组件为 null 时仍能构造；用作缓存键时，null 只和 null 相等。日期前后颠倒不会在这里被拒绝。相同组件重复构造相等。
 */
public record FundMetricCacheKey(FundCode fundCode, LocalDate startDate, LocalDate endDate,
        NavBasis navBasis, BigDecimal riskFreeRate, String dataVersion, String algorithmVersion) {}
