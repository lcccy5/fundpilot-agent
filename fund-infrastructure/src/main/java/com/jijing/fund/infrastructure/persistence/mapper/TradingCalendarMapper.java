package com.jijing.fund.infrastructure.persistence.mapper;

import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code trading_calendar} 的计数映射。表的主键是市场代码加日期，本接口不插入、不更新，因此没有缺行对象，也没有重复键写入。
 */
@Mapper
public interface TradingCalendarMapper {
    /**
     * 统计闭区间内 {@code trading_day = 1} 的天数。没有任何交易日时返回 0，不返回 null。
     * {@code market}、{@code start} 或 {@code end} 为 null 时比较结果为未知，计数同样是 0，和“区间内没有开市日”无法区分。
     */
    @Select("SELECT COUNT(*) FROM trading_calendar WHERE market_code=#{market} AND trading_day=1 AND trade_date BETWEEN #{start} AND #{end}")
    int countTradingDays(@Param("market") String market, @Param("start") LocalDate start, @Param("end") LocalDate end);
}
