package com.jijing.fund.scheduler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jijing.fund.application.FundMetricSnapshotUseCase;
import com.jijing.fund.application.dto.MetricSnapshotBatchResult;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 锁定指标快照调度在重算失败、结果为空和重复触发时的行为。
 * 截止日只来自上海当天，调用方不能传入空区间。
 */
class FundMetricSnapshotSchedulerTest {
    private FundMetricSnapshotUseCase useCase;
    private FundMetricSnapshotScheduler scheduler;

    /**
     * 准备重算用例和调度器。
     * null 用例的失败路径单独构造，不复用这个实例。
     */
    @BeforeEach
    void setUp() {
        useCase = Mockito.mock(FundMetricSnapshotUseCase.class);
        scheduler = new FundMetricSnapshotScheduler(useCase);
    }

    /**
     * 锁定连续两次重算都使用同一次读取到的上海日期。
     * 测试跨过上海零点时两次日期可能不同；同一天内若少调用一次，断言失败。
     */
    @Test
    void repeatsRecomputeForTheShanghaiDate() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        when(useCase.recomputeEnabledFunds(today)).thenReturn(new MetricSnapshotBatchResult(3, 3, 1));

        scheduler.recomputeSnapshots();
        scheduler.recomputeSnapshots();

        verify(useCase, times(2)).recomputeEnabledFunds(today);
    }

    /**
     * 锁定用例异常连续向外抛，并且每次都会再次重算。
     * 失败被写成零失败，或第二次被跳过时断言失败。
     */
    @Test
    void propagatesUseCaseFailuresOnRepeatedCalls() {
        when(useCase.recomputeEnabledFunds(any(LocalDate.class))).thenThrow(new IllegalStateException("snapshot down"));

        assertThatThrownBy(scheduler::recomputeSnapshots).isInstanceOf(IllegalStateException.class).hasMessage("snapshot down");
        assertThatThrownBy(scheduler::recomputeSnapshots).isInstanceOf(IllegalStateException.class);
        verify(useCase, times(2)).recomputeEnabledFunds(any(LocalDate.class));
    }

    /**
     * 锁定用例为 null 或结果为 null 时抛出空指针。
     * 若调度器改成吞掉空结果并打出成功日志，断言失败。
     */
    @Test
    void rejectsNullUseCaseAndNullResult() {
        assertThatThrownBy(() -> new FundMetricSnapshotScheduler(null).recomputeSnapshots()).isInstanceOf(NullPointerException.class);
        when(useCase.recomputeEnabledFunds(any(LocalDate.class))).thenReturn(null);
        assertThatThrownBy(scheduler::recomputeSnapshots).isInstanceOf(NullPointerException.class);
    }
}
