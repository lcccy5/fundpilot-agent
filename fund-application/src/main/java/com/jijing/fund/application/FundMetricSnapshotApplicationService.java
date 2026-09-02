package com.jijing.fund.application;

import com.jijing.fund.analytics.model.*;
import com.jijing.fund.analytics.port.FundMetricSnapshotRepository;
import com.jijing.fund.application.dto.MetricSnapshotBatchResult;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.repository.FundRepository;
import java.time.LocalDate;
import java.util.*;

public class FundMetricSnapshotApplicationService implements FundMetricSnapshotUseCase {
    private final FundRepository funds; private final FundMetricsQueryUseCase metrics; private final FundMetricSnapshotRepository snapshots;
    public FundMetricSnapshotApplicationService(FundRepository funds, FundMetricsQueryUseCase metrics, FundMetricSnapshotRepository snapshots){this.funds=funds;this.metrics=metrics;this.snapshots=snapshots;}
    @Override public MetricSnapshotBatchResult recomputeEnabledFunds(LocalDate end) {
        int offset=0,fundCount=0,snapshotCount=0,failures=0;
        while(true){List<FundCode> codes=funds.findEnabledFundCodes(offset,100);if(codes.isEmpty())break;
            for(FundCode code:codes){fundCount++;for(var period:periods(end).entrySet()){try{FundMetrics value=metrics.calculate(code.value(),period.getValue(),end,null);
                        snapshots.upsert(new FundMetricSnapshot(period.getKey(),value));snapshotCount++;}catch(RuntimeException ex){failures++;}}}
            if(codes.size()<100)break;offset+=codes.size();}
        return new MetricSnapshotBatchResult(fundCount,snapshotCount,failures);
    }
    private Map<String,LocalDate> periods(LocalDate end){Map<String,LocalDate> p=new LinkedHashMap<>();p.put("1M",end.minusMonths(1));p.put("3M",end.minusMonths(3));
        p.put("6M",end.minusMonths(6));p.put("1Y",end.minusYears(1));p.put("3Y",end.minusYears(3));p.put("5Y",end.minusYears(5));p.put("YTD",end.withDayOfYear(1));p.put("MAX",LocalDate.of(1900,1,1));return p;}
}
