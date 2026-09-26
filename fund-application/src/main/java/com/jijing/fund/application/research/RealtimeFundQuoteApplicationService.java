package com.jijing.fund.application.research;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.research.model.DataLineage;
import com.jijing.fund.domain.research.model.MarketDataKind;
import com.jijing.fund.domain.research.model.SourcedValue;
import com.jijing.fund.domain.research.provider.FundDiscoveryProvider;
import com.jijing.fund.domain.research.provider.MarketQuoteProvider;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 决定何时把关联 ETF 的交易所报价返回给调用方。HTTP 和供应商的传输对象不能进入本类。
 * 查询不区分用户，相同基金代码对所有调用方返回同一结果。
 */
public final class RealtimeFundQuoteApplicationService implements RealtimeFundQuoteUseCase {
    private static final String PROXY_LIMITATION = "关联ETF实时行情仅反映代理标的，不是场外基金官方净值或盘中估值";

    private final FundDiscoveryProvider discovery;
    private final MarketQuoteProvider quoteProvider;
    private final Clock clock;
    private final Duration maxQuoteAge;

    /**
     * 组装查询用例。发现器、报价器或时钟为空时抛出空指针异常。最大行情年龄为空、为零或为负时抛出参数异常，避免把所有报价都当成新鲜或过期。
     */
    public RealtimeFundQuoteApplicationService(FundDiscoveryProvider discovery, MarketQuoteProvider quoteProvider,
            Clock clock, Duration maxQuoteAge) {
        this.discovery = Objects.requireNonNull(discovery);
        this.quoteProvider = Objects.requireNonNull(quoteProvider);
        this.clock = Objects.requireNonNull(clock);
        if (maxQuoteAge == null || maxQuoteAge.isNegative() || maxQuoteAge.isZero()) {
            throw new IllegalArgumentException("maxQuoteAge must be positive");
        }
        this.maxQuoteAge = maxQuoteAge;
    }

    /**
     * 先找关联 ETF，再取它的最新报价。基金代码为空或不是六位数字时抛出参数异常或空指针异常。
     * 没有关联标的时返回 NO_EXCHANGE_PROXY；报价器返回空时返回 DATA_NOT_READY。这两种都不是系统异常。
     * 报价时间加上最大年龄仍早于当前时钟时标为 STALE，恰好等于边界时仍视为 AVAILABLE。
     * 发现器或报价器抛出的异常不会被转成业务状态。
     */
    @Override
    public RealtimeFundQuoteResult query(String fundCode) {
        FundCode code = new FundCode(fundCode);
        var linked = discovery.findLinkedExchangeFund(code);
        if (linked.isEmpty()) {
            return RealtimeFundQuoteResult.unavailable(RealtimeFundQuoteStatus.NO_EXCHANGE_PROXY, "该基金没有可用的关联场内ETF实时行情");
        }
        var quote = quoteProvider.latestQuote(linked.get().securityCode());
        if (quote.isEmpty()) {
            return RealtimeFundQuoteResult.unavailable(RealtimeFundQuoteStatus.DATA_NOT_READY, "关联ETF实时行情暂不可用");
        }
        var linkedFund = linked.get();
        var latest = quote.get();
        var value = new RealtimeFundQuoteView(code.value(), linkedFund.securityCode().code(), linkedFund.displayName(),
                latest.currentPrice(), latest.previousClose(), latest.priceChange(), latest.changePercent(),
                latest.quoteTime(), MarketDataKind.UNDERLYING_ETF_PROXY, PROXY_LIMITATION);
        var lineage = new DataLineage(List.of(linkedFund.provenance(), latest.provenance()), null);
        var status = latest.quoteTime().plus(maxQuoteAge).isBefore(clock.instant())
                ? RealtimeFundQuoteStatus.STALE
                : RealtimeFundQuoteStatus.AVAILABLE;
        var limitations = status == RealtimeFundQuoteStatus.STALE
                ? List.of(PROXY_LIMITATION, "QUOTE_STALE")
                : List.of(PROXY_LIMITATION);
        return new RealtimeFundQuoteResult(status, new SourcedValue<>(value, lineage), limitations);
    }
}
