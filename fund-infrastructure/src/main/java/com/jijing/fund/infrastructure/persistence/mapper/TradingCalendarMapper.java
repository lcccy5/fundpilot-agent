package com.jijing.fund.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.*;
import java.time.LocalDate;

@Mapper
public interface TradingCalendarMapper {
    @Select("SELECT COUNT(*) FROM trading_calendar WHERE market_code=#{market} AND trading_day=1 AND trade_date BETWEEN #{start} AND #{end}")
    int countTradingDays(@Param("market") String market, @Param("start") LocalDate start, @Param("end") LocalDate end);
}

