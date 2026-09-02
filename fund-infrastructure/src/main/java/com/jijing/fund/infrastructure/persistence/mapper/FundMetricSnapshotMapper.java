package com.jijing.fund.infrastructure.persistence.mapper;

import com.jijing.fund.infrastructure.persistence.entity.FundMetricSnapshotEntity;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FundMetricSnapshotMapper {
    @Insert("""
      INSERT INTO fund_metric_snapshot(fund_code,period_code,actual_start_date,actual_end_date,nav_basis,observation_count,coverage_rate,
      cumulative_return,annualized_return,annualized_volatility,max_drawdown,sharpe_ratio,positive_day_ratio,risk_free_rate,data_revision,algorithm_version,calculated_at)
      VALUES(#{fundCode},#{periodCode},#{actualStartDate},#{actualEndDate},#{navBasis},#{observationCount},#{coverageRate},#{cumulativeReturn},
      #{annualizedReturn},#{annualizedVolatility},#{maxDrawdown},#{sharpeRatio},#{positiveDayRatio},#{riskFreeRate},#{dataRevision},#{algorithmVersion},#{calculatedAt})
      ON DUPLICATE KEY UPDATE actual_start_date=VALUES(actual_start_date),actual_end_date=VALUES(actual_end_date),observation_count=VALUES(observation_count),
      coverage_rate=VALUES(coverage_rate),cumulative_return=VALUES(cumulative_return),annualized_return=VALUES(annualized_return),
      annualized_volatility=VALUES(annualized_volatility),max_drawdown=VALUES(max_drawdown),sharpe_ratio=VALUES(sharpe_ratio),
      positive_day_ratio=VALUES(positive_day_ratio),risk_free_rate=VALUES(risk_free_rate),calculated_at=VALUES(calculated_at)
      """) int upsert(FundMetricSnapshotEntity entity);
}

