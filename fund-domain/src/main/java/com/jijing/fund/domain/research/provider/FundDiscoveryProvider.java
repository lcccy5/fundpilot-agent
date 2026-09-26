package com.jijing.fund.domain.research.provider;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.research.model.LinkedExchangeFund;
import java.util.Optional;

/** 基金发现端口，负责为场外基金查找可作为参考代理的关联场内交易基金。 */
public interface FundDiscoveryProvider {
    /**
     * 查找与指定基金关联的场内交易基金；数据源确认没有关联基金时返回空。
     * 数据源不可用或返回不合规数据时，实现应抛出 {@link com.jijing.fund.domain.exception.ExternalDataSourceException}。
     */
    Optional<LinkedExchangeFund> findLinkedExchangeFund(FundCode fundCode);
}
