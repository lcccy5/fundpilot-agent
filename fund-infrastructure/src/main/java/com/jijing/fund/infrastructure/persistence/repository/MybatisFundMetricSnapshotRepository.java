package com.jijing.fund.infrastructure.persistence.repository;

import com.jijing.fund.analytics.model.*;
import com.jijing.fund.analytics.port.FundMetricSnapshotRepository;
import com.jijing.fund.infrastructure.persistence.entity.FundMetricSnapshotEntity;
import com.jijing.fund.infrastructure.persistence.mapper.FundMetricSnapshotMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisFundMetricSnapshotRepository implements FundMetricSnapshotRepository {
    private final FundMetricSnapshotMapper mapper;
    public MybatisFundMetricSnapshotRepository(FundMetricSnapshotMapper mapper){this.mapper=mapper;}
    @Override public void upsert(FundMetricSnapshot snapshot){FundMetrics m=snapshot.metrics();var e=new FundMetricSnapshotEntity();e.fundCode=m.fundCode().value();e.periodCode=snapshot.periodCode();
        e.actualStartDate=m.actualStartDate();e.actualEndDate=m.actualEndDate();e.navBasis=m.navBasis().name();e.observationCount=m.observationCount();e.coverageRate=m.coverage().rate();
        e.cumulativeReturn=value(m.cumulativeReturn());e.annualizedReturn=value(m.annualizedReturn());e.annualizedVolatility=value(m.annualizedVolatility());
        e.maxDrawdown=value(m.maxDrawdown());e.sharpeRatio=value(m.sharpeRatio());e.positiveDayRatio=value(m.positiveDayRatio());e.riskFreeRate=m.annualRiskFreeRate();
        e.dataRevision=Long.parseLong(m.dataVersion());e.algorithmVersion=m.algorithmVersion();e.calculatedAt=m.calculatedAt();mapper.upsert(e);}
    private java.math.BigDecimal value(MetricValue value){return value.status()==MetricStatus.AVAILABLE?value.value():null;}
}

