package com.jijing.fund.domain.cache;

import com.jijing.fund.domain.model.*;
import java.time.LocalDate;
import java.util.*;

/**
 * 基金查询结果的缓存端口，缓存基金档案和指定日期区间的净值序列，用于减少对数据库和外部数据源的重复访问。
 * 接口本身不做参数校验；传入 null 的基金代码或日期时的行为由实现决定，调用方应只传入已校验的领域对象。
 */
public interface FundQueryCache {
    /**
     * 按基金代码读取缓存中的基金档案。
     * 未命中或已失效时返回 {@link Optional#empty()}，不会回源加载；code 为 null 时实现通常直接抛出 NullPointerException。
     */
    Optional<FundProfile> getProfile(FundCode code);

    /**
     * 以档案自身的基金代码为键写入或覆盖缓存。
     * profile 为 null 时实现通常抛出 NullPointerException；重复写入同一基金会覆盖旧值。
     */
    void putProfile(FundProfile profile);

    /**
     * 读取某基金在 [startDate, endDate] 区间内的净值缓存，区间必须与写入时完全一致才会命中。
     * 未命中或过期返回 {@link Optional#empty()}；命中时返回的列表可能为空列表，表示该区间确实没有净值。
     */
    Optional<List<NavPoint>> getHistory(FundCode code, LocalDate startDate, LocalDate endDate);

    /**
     * 写入某基金指定区间的净值序列，重复写入同一区间会覆盖旧值。
     * points 为 null 时实现通常在复制列表时抛出 NullPointerException。
     */
    void putHistory(FundCode code, LocalDate startDate, LocalDate endDate, List<NavPoint> points);

    /**
     * 清除某基金的档案缓存和所有区间的净值缓存，通常在数据同步后调用。
     * 基金不存在于缓存中时不报错，可重复调用。
     */
    void evict(FundCode code);
}
