package com.jijing.fund.scheduler;

import com.jijing.fund.application.FundMetricSnapshotUseCase;
import java.time.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "fund.metrics.snapshot", name = "enabled", havingValue = "true")
public class FundMetricSnapshotScheduler {
    private static final Logger log = LoggerFactory.getLogger(FundMetricSnapshotScheduler.class);
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private final FundMetricSnapshotUseCase useCase;

    public FundMetricSnapshotScheduler(FundMetricSnapshotUseCase useCase) { this.useCase = useCase; }

    @Scheduled(cron = "${fund.metrics.snapshot.cron}", zone = "Asia/Shanghai")
    public void recomputeSnapshots() {
        var result = useCase.recomputeEnabledFunds(LocalDate.now(CHINA));
        log.info("operation=fundMetricSnapshot funds={} snapshots={} failures={}",
                result.funds(), result.snapshots(), result.failures());
    }
}
