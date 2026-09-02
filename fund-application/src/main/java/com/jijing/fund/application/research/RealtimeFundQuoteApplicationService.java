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

/** Application policy for the ETF proxy quote. HTTP and provider DTOs stay outside this class. */
public final class RealtimeFundQuoteApplicationService implements RealtimeFundQuoteUseCase {
    private static final String PROXY_LIMITATION = "关联ETF实时行情仅反映代理标的，不是场外基金官方净值或盘中估值";
    private final FundDiscoveryProvider discovery;
    private final MarketQuoteProvider quoteProvider;
    private final Clock clock;
    private final Duration maxQuoteAge;

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

    @Override
    public RealtimeFundQuoteResult query(String fundCode) {
        FundCode code = new FundCode(fundCode);
        var linked = discovery.findLinkedExchangeFund(code);
        if (linked.isEmpty()) return RealtimeFundQuoteResult.unavailable(RealtimeFundQuoteStatus.NO_EXCHANGE_PROXY,
                "该基金没有可用的关联场内ETF实时行情");
        var quote = quoteProvider.latestQuote(linked.get().securityCode());
        if (quote.isEmpty()) return RealtimeFundQuoteResult.unavailable(RealtimeFundQuoteStatus.DATA_NOT_READY,
                "关联ETF实时行情暂不可用");
        var value = new RealtimeFundQuoteView(code.value(), linked.get().securityCode().code(), linked.get().displayName(),
                quote.get().currentPrice(), quote.get().previousClose(), quote.get().priceChange(), quote.get().changePercent(),
                quote.get().quoteTime(), MarketDataKind.UNDERLYING_ETF_PROXY, PROXY_LIMITATION);
        var lineage = new DataLineage(List.of(linked.get().provenance(), quote.get().provenance()), null);
        var status = quote.get().quoteTime().plus(maxQuoteAge).isBefore(clock.instant())
                ? RealtimeFundQuoteStatus.STALE : RealtimeFundQuoteStatus.AVAILABLE;
        var limitations = status == RealtimeFundQuoteStatus.STALE
                ? List.of(PROXY_LIMITATION, "QUOTE_STALE") : List.of(PROXY_LIMITATION);
        return new RealtimeFundQuoteResult(status, new SourcedValue<>(value, lineage), limitations);
    }
}
