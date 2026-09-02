package com.jijing.fund.domain.repository;

import java.time.LocalDate;

public interface TradingCalendarRepository {
    int countTradingDays(String marketCode, LocalDate startDate, LocalDate endDate);
}

