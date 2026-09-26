package com.jijing.fund.application;

import com.jijing.fund.application.dto.FundSyncResult;
import java.time.LocalDate;

/**
 * 把单只基金的外部档案与净值同步进本地仓储。
 * 同一基金已有同步在进行、区间非法或上游没有该基金时拒绝本次写入。
 */
public interface FundSyncUseCase {

    /**
     * 在互斥锁内拉取并校验一只基金的档案与净值，成功后增加数据版本并安排缓存失效。
     * 代码非法或区间缺失、颠倒时抛出无效查询；锁被占用时抛出同步已在进行且不会解锁别人持有的锁；上游没有档案时抛出基金不存在。
     * 上游数据质量不合格或外部数据源失败时，先记下失败审计再把原异常抛出，并在离开前释放锁。
     *
     * @param fundCode 调用方传入的基金代码文本
     * @param startDate 拉取起点，含当日
     * @param endDate 拉取终点，含当日
     * @return 本次拉取条数、实际写入条数以及成功状态
     */
    FundSyncResult syncFund(String fundCode, LocalDate startDate, LocalDate endDate);
}
