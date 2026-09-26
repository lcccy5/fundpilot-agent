package com.jijing.fund.application.dto;

/**
 * 一批指标快照重算的计数结果。
 * 单个周期失败只增加失败数，不阻止其余周期写入。
 *
 * @param funds 本批处理的已启用基金数
 * @param snapshots 成功写入的快照数
 * @param failures 计算或写入时捕获的运行时失败次数
 */
public record MetricSnapshotBatchResult(int funds, int snapshots, int failures) {
}
