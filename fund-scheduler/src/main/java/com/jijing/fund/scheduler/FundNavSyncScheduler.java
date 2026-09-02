package com.jijing.fund.scheduler;

import com.jijing.fund.application.FundBatchSyncUseCase;
import java.time.LocalDate;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="fund.sync", name="enabled", havingValue="true")
public class FundNavSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(FundNavSyncScheduler.class);
    private final FundBatchSyncUseCase useCase;
    public FundNavSyncScheduler(FundBatchSyncUseCase useCase) { this.useCase = useCase; }
    @Scheduled(cron="${fund.sync.cron}", zone="Asia/Shanghai")
    public void syncDailyNav() {
        LocalDate end = LocalDate.now(ZoneIdHolder.CHINA);
        var result = useCase.syncEnabledFunds(end.minusDays(7), end);
        log.info("operation=fundDailySync total={} succeeded={} failed={}", result.total(), result.succeeded(), result.failed());
    }
    private static final class ZoneIdHolder { private static final java.time.ZoneId CHINA = java.time.ZoneId.of("Asia/Shanghai"); }
}
