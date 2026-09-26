package com.jijing.fund.infrastructure.persistence.mapper;

import com.jijing.fund.infrastructure.persistence.entity.FundMetricSnapshotEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code fund_metric_snapshot} 的写入映射。没有查询方法，无法从这里观察缺行。
 * 唯一键冲突时覆盖区间端点、观测数、各项可空指标、无风险利率和计算时间，不改唯一键本身。
 */
@Mapper
public interface FundMetricSnapshotMapper {
    /**
     * 按指标身份插入或覆盖一行。实体各字段为 null 时绑定 SQL NULL；非空列因此失败，可空收益列则保存为 NULL。
     * 同一身份再次写入时，可空指标里的 null 会覆盖上一版的数字。返回驱动报告的受影响行数，调用方目前不使用。
     * 身份以外的唯一约束若仍冲突，异常向上抛出。
     */
    @Insert("""
      INSERT INTO fund_metric_snapshot(fund_code,period_code,actual_start_date,actual_end_date,nav_basis,observation_count,coverage_rate,
      cumulative_return,annualized_return,annualized_volatility,max_drawdown,sharpe_ratio,positive_day_ratio,risk_free_rate,data_revision,algorithm_version,calculated_at)
      VALUES(#{fundCode},#{periodCode},#{actualStartDate},#{actualEndDate},#{navBasis},#{observationCount},#{coverageRate},#{cumulativeReturn},
      #{annualizedReturn},#{annualizedVolatility},#{maxDrawdown},#{sharpeRatio},#{positiveDayRatio},#{riskFreeRate},#{dataRevision},#{algorithmVersion},#{calculatedAt})
      ON DUPLICATE KEY UPDATE actual_start_date=VALUES(actual_start_date),actual_end_date=VALUES(actual_end_date),observation_count=VALUES(observation_count),
      coverage_rate=VALUES(coverage_rate),cumulative_return=VALUES(cumulative_return),annualized_return=VALUES(annualized_return),
      annualized_volatility=VALUES(annualized_volatility),max_drawdown=VALUES(max_drawdown),sharpe_ratio=VALUES(sharpe_ratio),
      positive_day_ratio=VALUES(positive_day_ratio),risk_free_rate=VALUES(risk_free_rate),calculated_at=VALUES(calculated_at)
      """)
    int upsert(FundMetricSnapshotEntity entity);
}
