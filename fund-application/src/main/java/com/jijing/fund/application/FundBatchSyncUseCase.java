package com.jijing.fund.application;

import com.jijing.fund.application.dto.BatchSyncResult;
import java.time.LocalDate;

/**
 * 按页编排已启用基金的区间同步。
 * 单只失败应被计入结果而不是中断整批；日期不合法时的具体失败由单只同步端口决定。
 */
public interface FundBatchSyncUseCase {

    /**
     * 同步当前已启用的全部基金在给定区间内的档案与净值。
     * 区间颠倒、代码非法或上游拒绝时，对应基金记为失败并继续下一只；启用列表为空时返回三项计数均为零的结果。
     *
     * @param startDate 区间起点，含当日
     * @param endDate 区间终点，含当日
     * @return 本批尝试总数、成功数与失败数
     */
    BatchSyncResult syncEnabledFunds(LocalDate startDate, LocalDate endDate);
}
