package com.jijing.fund.scheduler;

import com.jijing.fund.application.FundMetricSnapshotUseCase;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 在配置开启时，按上海时区的当天重算已启用基金的指标快照。
 * 用例失败会原样冒出。调度器不记录上次是否成功，因此重复触发会再次重算。
 */
@Component
@ConditionalOnProperty(prefix = "fund.metrics.snapshot", name = "enabled", havingValue = "true")
public class FundMetricSnapshotScheduler {
    private static final Logger log = LoggerFactory.getLogger(FundMetricSnapshotScheduler.class);
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private final FundMetricSnapshotUseCase useCase;

    /**
     * 保存快照重算用例。
     * 不在构造时重算。用例为 null 时，要到执行时才抛出 {@link NullPointerException}。
     */
    public FundMetricSnapshotScheduler(FundMetricSnapshotUseCase useCase) {
        this.useCase = useCase;
    }

    /**
     * 以亚洲上海的当天为截止日，重算已启用基金的指标快照并写下基金数、快照数和失败数。
     * 用例异常原样向外抛，不会被记成零失败。结果为 null 时，在读取计数字段处抛出 {@link NullPointerException}。
     * 重复调用会再次重算，不因上一轮失败而跳过。
     */
    @Scheduled(cron = "${fund.metrics.snapshot.cron}", zone = "Asia/Shanghai")
    public void recomputeSnapshots() {
        var result = useCase.recomputeEnabledFunds(LocalDate.now(CHINA));
        log.info("operation=fundMetricSnapshot funds={} snapshots={} failures={}",
                result.funds(), result.snapshots(), result.failures());
    }
}
