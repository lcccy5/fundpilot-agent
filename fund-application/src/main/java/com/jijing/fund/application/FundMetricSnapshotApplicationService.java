package com.jijing.fund.application;

import com.jijing.fund.analytics.model.FundMetricSnapshot;
import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.port.FundMetricSnapshotRepository;
import com.jijing.fund.application.dto.MetricSnapshotBatchResult;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.repository.FundRepository;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 为已启用基金批量重算预设周期的指标快照。
 * 单只基金的单个周期失败只增加失败计数，分页在不足一整页时结束。
 */
public class FundMetricSnapshotApplicationService implements FundMetricSnapshotUseCase {
    private final FundRepository funds;
    private final FundMetricsQueryUseCase metrics;
    private final FundMetricSnapshotRepository snapshots;

    /**
     * 装配基金目录、指标计算与快照仓储。
     * 不在构造时访问外部资源；依赖为空时留到重算时失败。
     */
    public FundMetricSnapshotApplicationService(FundRepository funds, FundMetricsQueryUseCase metrics,
            FundMetricSnapshotRepository snapshots) {
        this.funds = funds;
        this.metrics = metrics;
        this.snapshots = snapshots;
    }

    /**
     * 逐页读取已启用基金，并为每个预设周期计算、写入快照。
     * 计算抛出任何运行时异常时该周期记为失败并继续；截止日期为 null 时在生成周期起点处失败。没有已启用基金时返回全零计数。
     */
    @Override
    public MetricSnapshotBatchResult recomputeEnabledFunds(LocalDate end) {
        int offset = 0;
        int fundCount = 0;
        int snapshotCount = 0;
        int failures = 0;
        while (true) {
            List<FundCode> codes = funds.findEnabledFundCodes(offset, 100);
            if (codes.isEmpty()) {
                break;
            }
            for (FundCode code : codes) {
                fundCount++;
                for (var period : periods(end).entrySet()) {
                    try {
                        FundMetrics value = metrics.calculate(code.value(), period.getValue(), end, null);
                        snapshots.upsert(new FundMetricSnapshot(period.getKey(), value));
                        snapshotCount++;
                    } catch (RuntimeException ex) {
                        failures++;
                    }
                }
            }
            if (codes.size() < 100) {
                break;
            }
            offset += codes.size();
        }
        return new MetricSnapshotBatchResult(fundCount, snapshotCount, failures);
    }

    /**
     * 生成相对截止日期的预设周期起点，保持插入顺序以便结果稳定。
     * 截止日期为 null 时日期运算失败；不检查起点是否晚于终点，超长历史周期交给后续计算自行失败并被计数。
     */
    private Map<String, LocalDate> periods(LocalDate end) {
        Map<String, LocalDate> periods = new LinkedHashMap<>();
        periods.put("1M", end.minusMonths(1));
        periods.put("3M", end.minusMonths(3));
        periods.put("6M", end.minusMonths(6));
        periods.put("1Y", end.minusYears(1));
        periods.put("3Y", end.minusYears(3));
        periods.put("5Y", end.minusYears(5));
        periods.put("YTD", end.withDayOfYear(1));
        periods.put("MAX", LocalDate.of(1900, 1, 1));
        return periods;
    }
}
