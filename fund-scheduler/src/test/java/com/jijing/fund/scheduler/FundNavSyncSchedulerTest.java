package com.jijing.fund.scheduler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jijing.fund.application.FundBatchSyncUseCase;
import com.jijing.fund.application.dto.BatchSyncResult;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 锁定净值同步调度在用例失败、结果为空和重复触发时的行为。
 * 调度器不接收外部区间，因此没有空序列或非法起止日可以传入。
 */
class FundNavSyncSchedulerTest {
    private FundBatchSyncUseCase useCase;
    private FundNavSyncScheduler scheduler;

    /**
     * 准备用例替身和调度器。
     * 用例为 null 的失败路径另建实例，避免污染这里的重复调用计数。
     */
    @BeforeEach
    void setUp() {
        useCase = Mockito.mock(FundBatchSyncUseCase.class);
        scheduler = new FundNavSyncScheduler(useCase);
    }

    /**
     * 锁定连续两次同步都使用上海当天向前 7 天到当天的窗口。
     * 窗口被改短、改长，或第二次被去重跳过时断言失败。跨日执行时两次日期可能不同，那是时钟前进而不是去重。
     */
    @Test
    void repeatsSyncForTheShanghaiLookbackWindow() {
        LocalDate end = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        when(useCase.syncEnabledFunds(end.minusDays(7), end)).thenReturn(new BatchSyncResult(2, 1, 1));

        scheduler.syncDailyNav();
        scheduler.syncDailyNav();

        verify(useCase, times(2)).syncEnabledFunds(end.minusDays(7), end);
    }

    /**
     * 锁定用例异常会连续向外抛出，并且每次触发都会再次调用用例。
     * 异常被吞掉或第二次不再调用时断言失败。
     */
    @Test
    void propagatesUseCaseFailuresOnRepeatedCalls() {
        when(useCase.syncEnabledFunds(any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("sync down"));

        assertThatThrownBy(scheduler::syncDailyNav).isInstanceOf(IllegalStateException.class).hasMessage("sync down");
        assertThatThrownBy(scheduler::syncDailyNav).isInstanceOf(IllegalStateException.class);
        verify(useCase, times(2)).syncEnabledFunds(any(LocalDate.class), any(LocalDate.class));
    }

    /**
     * 锁定用例为 null 或返回 null 时抛出空指针。
     * 这两种输入若被记成零失败的成功同步，断言失败。
     */
    @Test
    void rejectsNullUseCaseAndNullResult() {
        assertThatThrownBy(() -> new FundNavSyncScheduler(null).syncDailyNav()).isInstanceOf(NullPointerException.class);
        when(useCase.syncEnabledFunds(any(LocalDate.class), any(LocalDate.class))).thenReturn(null);
        assertThatThrownBy(scheduler::syncDailyNav).isInstanceOf(NullPointerException.class);
    }
}
