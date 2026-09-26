package com.jijing.fund.infrastructure.persistence.repository;

import com.jijing.fund.domain.repository.TradingCalendarRepository;
import com.jijing.fund.infrastructure.persistence.mapper.TradingCalendarMapper;
import java.time.LocalDate;
import org.springframework.stereotype.Repository;

/**
 * 用 {@code trading_calendar} 统计开市日。只读计数，不保存日历行，因此没有缺行对象，也没有重复键写入。
 * 没有任何交易日、市场或日期为 null 时，底层计数都是 0。
 */
@Repository
public class MybatisTradingCalendarRepository implements TradingCalendarRepository {
    private final TradingCalendarMapper mapper;

    /**
     * 保存交易日历映射器。这里不访问数据库。映射器为 null 时，计数调用会抛出空指针。
     */
    public MybatisTradingCalendarRepository(TradingCalendarMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 返回闭区间内的开市日数量。映射器在没有命中时返回 0，本方法原样返回，不把 0 变成空可选值。
     * {@code market}、{@code start}、{@code end} 为 null 时仍会传给映射器，结果同样是 0。
     */
    @Override
    public int countTradingDays(String market, LocalDate start, LocalDate end) {
        return mapper.countTradingDays(market, start, end);
    }
}
