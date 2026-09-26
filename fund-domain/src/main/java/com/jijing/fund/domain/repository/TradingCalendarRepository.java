package com.jijing.fund.domain.repository;

import java.time.LocalDate;

/** 交易日历查询端口，按市场统计区间内的交易日数量，可作为净值序列应有条数的参照。 */
public interface TradingCalendarRepository {
    /**
     * 统计某市场在 [startDate, endDate] 区间内的交易日数量。
     * 市场代码未知、日历未覆盖该区间或起始日期晚于结束日期时返回 0，调用方不能据此区分“无交易日”和“无日历数据”。
     */
    int countTradingDays(String marketCode, LocalDate startDate, LocalDate endDate);
}
