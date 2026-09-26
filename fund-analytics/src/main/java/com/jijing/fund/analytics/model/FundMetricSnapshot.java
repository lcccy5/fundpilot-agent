package com.jijing.fund.analytics.model;

/**
 * 把一份基金指标绑到期间编码上，供快照存储使用。
 * 不重新计算，也不校验期间编码或指标是否为 null。null 组件仍能构造成功，失败会推迟到写入或读取时。
 */
public record FundMetricSnapshot(String periodCode, FundMetrics metrics) {}
