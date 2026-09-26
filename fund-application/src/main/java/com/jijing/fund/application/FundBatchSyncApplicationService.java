package com.jijing.fund.application;

import com.jijing.fund.application.dto.BatchSyncResult;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.repository.FundRepository;
import java.time.LocalDate;
import java.util.List;

/**
 * 已启用基金的批量同步编排。
 * 每只基金都通过单只同步端口执行，从而走独立的事务代理；一只失败不会取消其余基金。
 */
public class FundBatchSyncApplicationService implements FundBatchSyncUseCase {
    private final FundRepository repository;
    private final FundSyncUseCase singleSync;

    /**
     * 装配基金目录和单只同步端口。
     * 不在构造时加锁或访问外部数据源。
     */
    public FundBatchSyncApplicationService(FundRepository repository, FundSyncUseCase singleSync) {
        this.repository = repository;
        this.singleSync = singleSync;
    }

    /**
     * 按页同步已启用基金。单只同步抛出的运行时异常记为失败并继续下一只。
     * 启用列表为空时返回全零计数。日期或代码问题不会在本层提前拦截，而是表现为对应基金失败。
     */
    @Override
    public BatchSyncResult syncEnabledFunds(LocalDate startDate, LocalDate endDate) {
        int offset = 0;
        int total = 0;
        int succeeded = 0;
        int failed = 0;
        while (true) {
            List<FundCode> codes = repository.findEnabledFundCodes(offset, 100);
            if (codes.isEmpty()) {
                break;
            }
            for (FundCode code : codes) {
                total++;
                try {
                    singleSync.syncFund(code.value(), startDate, endDate);
                    succeeded++;
                } catch (RuntimeException ex) {
                    failed++;
                }
            }
            if (codes.size() < 100) {
                break;
            }
            offset += codes.size();
        }
        return new BatchSyncResult(total, succeeded, failed);
    }
}
