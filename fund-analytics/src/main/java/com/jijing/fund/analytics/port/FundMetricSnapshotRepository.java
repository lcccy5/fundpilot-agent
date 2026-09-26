package com.jijing.fund.analytics.port;

import com.jijing.fund.analytics.model.FundMetricSnapshot;

/**
 * 持久化按期间编码索引的基金指标快照。
 * 不负责计算。写入失败时的异常类型由实现抛出，接口本身不返回成功标记。
 */
public interface FundMetricSnapshotRepository {

    /**
     * 按快照中的期间覆盖保存。
     * 同一期间重复调用应替换旧快照而不是追加。快照为 null，或其中指标缺失时，由实现拒绝或抛出运行时异常。
     */
    void upsert(FundMetricSnapshot snapshot);
}
