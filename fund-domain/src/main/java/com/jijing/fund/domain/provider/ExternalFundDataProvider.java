package com.jijing.fund.domain.provider;

import com.jijing.fund.domain.model.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 外部基金数据采集端口，负责从第三方数据源拉取基金档案和历史净值并转换为领域对象。
 * 第三方 DTO 不得越过 Infrastructure 边界；数据源不可用、超时或返回不合规数据时，实现应抛出
 * {@link com.jijing.fund.domain.exception.ExternalDataSourceException}，而不是返回 null。
 */
public interface ExternalFundDataProvider {
    /** 拉取基金档案；数据源确认该基金不存在时返回空，调用失败时抛出 ExternalDataSourceException。 */
    Optional<FundProfile> fetchProfile(FundCode fundCode);

    /**
     * 拉取 [startDate, endDate] 区间内的净值序列；区间内没有净值时返回空列表。
     * 调用失败或返回的数据无法构造成合法 {@link NavPoint} 时抛出 ExternalDataSourceException。
     */
    List<NavPoint> fetchNavHistory(FundCode fundCode, LocalDate startDate, LocalDate endDate);

    /** 返回数据源名称，写入 dataSource 字段和同步审计记录；应为稳定的非空字符串。 */
    String sourceName();
}
