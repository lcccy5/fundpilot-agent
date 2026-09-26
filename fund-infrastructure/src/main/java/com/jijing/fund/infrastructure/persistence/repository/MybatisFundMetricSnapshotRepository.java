package com.jijing.fund.infrastructure.persistence.repository;

import com.jijing.fund.analytics.model.FundMetricSnapshot;
import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.model.MetricStatus;
import com.jijing.fund.analytics.model.MetricValue;
import com.jijing.fund.analytics.port.FundMetricSnapshotRepository;
import com.jijing.fund.infrastructure.persistence.entity.FundMetricSnapshotEntity;
import com.jijing.fund.infrastructure.persistence.mapper.FundMetricSnapshotMapper;
import java.math.BigDecimal;
import org.springframework.stereotype.Repository;

/**
 * 把指标快照写入 {@code fund_metric_snapshot}。写之前不查询旧行，缺行和已存在行都走同一条 upsert。
 * 身份重复时由数据库覆盖指标列。本类不读取受影响行数，也不捕获重复键异常。
 * 最佳日收益、最差日收益和回撤区间没有对应列，写入时丢弃。
 */
@Repository
public class MybatisFundMetricSnapshotRepository implements FundMetricSnapshotRepository {
    private final FundMetricSnapshotMapper mapper;

    /**
     * 保存指标快照映射器。这里不访问数据库。映射器为 null 时，写入会抛出空指针。
     */
    public MybatisFundMetricSnapshotRepository(FundMetricSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 把一份快照绑定成表行并 upsert。不可用指标写成 null，覆盖率未知时覆盖率列为 null。
     * {@code snapshot}、其指标、基金代码、净值口径或覆盖率对象为 null 时，在绑定过程中抛出空指针，不会调用映射器。
     * 数据版本不是十进制长整型，或为 null 时，抛出 {@link NumberFormatException}，同样不会调用映射器。
     * 无风险利率为 null 时仍会调用映射器，由非空列在数据库拒绝。映射器抛出的重复键异常原样上抛。
     */
    @Override
    public void upsert(FundMetricSnapshot snapshot) {
        FundMetrics m = snapshot.metrics();
        FundMetricSnapshotEntity e = new FundMetricSnapshotEntity();
        e.fundCode = m.fundCode().value();
        e.periodCode = snapshot.periodCode();
        e.actualStartDate = m.actualStartDate();
        e.actualEndDate = m.actualEndDate();
        e.navBasis = m.navBasis().name();
        e.observationCount = m.observationCount();
        e.coverageRate = m.coverage().rate();
        e.cumulativeReturn = value(m.cumulativeReturn());
        e.annualizedReturn = value(m.annualizedReturn());
        e.annualizedVolatility = value(m.annualizedVolatility());
        e.maxDrawdown = value(m.maxDrawdown());
        e.sharpeRatio = value(m.sharpeRatio());
        e.positiveDayRatio = value(m.positiveDayRatio());
        e.riskFreeRate = m.annualRiskFreeRate();
        e.dataRevision = Long.parseLong(m.dataVersion());
        e.algorithmVersion = m.algorithmVersion();
        e.calculatedAt = m.calculatedAt();
        mapper.upsert(e);
    }

    /**
     * 取出可落库的指标数字。状态不是 {@code AVAILABLE} 时返回 null，使可空列保存 SQL NULL。
     * 状态为可用时返回其中的数字，该数字本身仍可能是 null。{@code value} 或状态为 null 时抛出空指针。
     */
    private BigDecimal value(MetricValue value) {
        return value.status() == MetricStatus.AVAILABLE ? value.value() : null;
    }
}
