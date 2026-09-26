package com.jijing.fund.application;

import com.jijing.fund.application.dto.MetricSnapshotBatchResult;
import java.time.LocalDate;

/**
 * 为已启用基金重算固定周期的指标快照并落库。
 * 单只或单个周期计算失败时记入失败数，不让整批中断。
 */
public interface FundMetricSnapshotUseCase {

    /**
     * 按固定页大小遍历已启用基金，为每个预设周期写入一份指标快照。
     * 某周期计算抛出运行时异常时该周期记失败并继续；启用列表为空时三项计数均为零。截止日期无法做日期运算时由日期类型自身失败。
     *
     * @param endDate 各周期共用的区间终点
     * @return 处理的基金数、成功写入的快照数、失败的周期次数
     */
    MetricSnapshotBatchResult recomputeEnabledFunds(LocalDate endDate);
}
