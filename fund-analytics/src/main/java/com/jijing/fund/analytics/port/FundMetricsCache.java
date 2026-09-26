package com.jijing.fund.analytics.port;

import com.jijing.fund.analytics.model.FundMetricCacheKey;
import com.jijing.fund.analytics.model.FundMetrics;
import java.util.Optional;

/**
 * 按缓存键读取或覆盖整份基金指标。
 * 接口不规定过期和并发策略。键或指标为 null 时是返回空、覆盖，还是抛出异常，由实现决定。
 */
public interface FundMetricsCache {

    /**
     * 按键取出已缓存指标。
     * 未命中时返回空可选值，不抛出“未找到”。键为 null 时由实现决定是空结果还是空指针。重复读取未变更的键应得到相等内容。
     */
    Optional<FundMetrics> get(FundMetricCacheKey key);

    /**
     * 用键覆盖写入一份指标。
     * 同一键重复写入以后一次为准。键或指标为 null 时由实现决定是否拒绝。
     */
    void put(FundMetricCacheKey key, FundMetrics metrics);
}
