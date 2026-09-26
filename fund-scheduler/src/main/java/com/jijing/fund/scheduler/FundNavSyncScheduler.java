package com.jijing.fund.scheduler;

import com.jijing.fund.application.FundBatchSyncUseCase;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 在配置开启时，按上海时区的当天回看一周并同步已启用基金净值。
 * 用例异常会原样冒出，调度器不吞掉失败，也不会把失败改写成成功日志。重复触发会再次同步，不做日内去重。
 */
@Component
@ConditionalOnProperty(prefix = "fund.sync", name = "enabled", havingValue = "true")
public class FundNavSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(FundNavSyncScheduler.class);
    private final FundBatchSyncUseCase useCase;

    /**
     * 保存批量同步用例。
     * 不在构造时访问用例。用例为 null 时，要到真正同步才抛出 {@link NullPointerException}。
     */
    public FundNavSyncScheduler(FundBatchSyncUseCase useCase) {
        this.useCase = useCase;
    }

    /**
     * 以亚洲上海的当天为结束日，同步含结束日在内向前 7 天的已启用基金净值。
     * 用例抛出运行时异常时异常继续向外抛，并且不会补写成功日志。结果为 null 时，在读取计数处抛出 {@link NullPointerException}。
     * 调用方不能改写窗口；重复调用会按当时的上海日期再次同步。
     */
    @Scheduled(cron = "${fund.sync.cron}", zone = "Asia/Shanghai")
    public void syncDailyNav() {
        LocalDate end = LocalDate.now(ZoneIdHolder.CHINA);
        var result = useCase.syncEnabledFunds(end.minusDays(7), end);
        log.info("operation=fundDailySync total={} succeeded={} failed={}", result.total(), result.succeeded(), result.failed());
    }

    /**
     * 持有亚洲上海时区，避免在同步路径上反复解析时区编号。
     * 编号无法识别时类加载失败；加载成功后没有额外失败分支。
     */
    private static final class ZoneIdHolder {
        private static final java.time.ZoneId CHINA = java.time.ZoneId.of("Asia/Shanghai");
    }
}
