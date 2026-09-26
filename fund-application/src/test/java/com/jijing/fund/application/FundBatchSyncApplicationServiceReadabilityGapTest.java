package com.jijing.fund.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jijing.fund.application.dto.BatchSyncResult;
import com.jijing.fund.application.exception.FundNotFoundException;
import com.jijing.fund.application.exception.InvalidFundQueryException;
import com.jijing.fund.application.exception.SyncAlreadyRunningException;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.repository.FundRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 确认批量同步把单只冲突、缺失和非法命令计入失败并继续处理其余基金。
 */
class FundBatchSyncApplicationServiceReadabilityGapTest {
    private final FundRepository repository = mock(FundRepository.class);
    private final FundSyncUseCase singleSync = mock(FundSyncUseCase.class);
    private final FundBatchSyncApplicationService service = new FundBatchSyncApplicationService(repository, singleSync);
    private final LocalDate start = LocalDate.of(2026, 1, 1);
    private final LocalDate end = LocalDate.of(2026, 1, 31);

    /**
     * 锁冲突、基金不存在和非法区间都算作该基金失败，不中断同页的后续基金。
     */
    @Test
    void countsConflictMissingAndInvalidCommandsAsFailures() {
        when(repository.findEnabledFundCodes(0, 100)).thenReturn(List.of(
                new FundCode("000001"), new FundCode("000002"), new FundCode("000003")));
        when(singleSync.syncFund("000001", start, end)).thenThrow(new SyncAlreadyRunningException("000001"));
        when(singleSync.syncFund("000002", start, end)).thenThrow(new FundNotFoundException("000002"));
        when(singleSync.syncFund("000003", start, end))
                .thenThrow(new InvalidFundQueryException("Invalid sync date range"));

        BatchSyncResult result = service.syncEnabledFunds(start, end);

        assertEquals(3, result.total());
        assertEquals(0, result.succeeded());
        assertEquals(3, result.failed());
    }
}
