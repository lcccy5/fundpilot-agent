package com.jijing.fund.infrastructure.persistence.repository;

import com.jijing.fund.domain.repository.TradingCalendarRepository;
import com.jijing.fund.infrastructure.persistence.mapper.TradingCalendarMapper;
import java.time.LocalDate;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisTradingCalendarRepository implements TradingCalendarRepository {
    private final TradingCalendarMapper mapper;
    public MybatisTradingCalendarRepository(TradingCalendarMapper mapper){this.mapper=mapper;}
    @Override public int countTradingDays(String market, LocalDate start, LocalDate end){return mapper.countTradingDays(market,start,end);}
}

