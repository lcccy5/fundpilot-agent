package com.jijing.fund.application.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.research.model.DataLineage;
import com.jijing.fund.domain.research.model.DataProvenance;
import com.jijing.fund.domain.research.model.ExchangeSecurityCode;
import com.jijing.fund.domain.research.model.LinkedExchangeFund;
import com.jijing.fund.domain.research.model.MarketDataKind;
import com.jijing.fund.domain.research.model.ProviderId;
import com.jijing.fund.domain.research.model.QualityStatus;
import com.jijing.fund.domain.research.model.SourcedValue;
import com.jijing.fund.domain.research.provider.FundDiscoveryProvider;
import com.jijing.fund.domain.research.provider.MarketQuoteProvider;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 行情查询的失败路径：报价缺失、供应商抛错、非法基金代码，以及结果对象拒绝不一致的状态。
 * 本用例没有用户参数，账户隔离不在这里覆盖。
 */
class RealtimeFundQuoteReadabilityGapTest {
    private static final Instant NOW = Instant.parse("2026-08-27T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /**
     * 关联标的存在但报价器返回空时，结果是数据未就绪且不带价格。报价器抛出的异常保持原样。
     */
    @Test
    void missingQuoteIsDataNotReadyAndProviderFailuresPropagate() {
        var missing = service(code -> Optional.of(linked(code)), security -> Optional.empty());
        var result = missing.query("000001");
        assertThat(result.status()).isEqualTo(RealtimeFundQuoteStatus.DATA_NOT_READY);
        assertThat(result.quote()).isNull();
        assertThat(result.limitations()).contains("关联ETF实时行情暂不可用");

        var brokenQuote = service(code -> Optional.of(linked(code)), security -> {
            throw new IllegalStateException("quote down");
        });
        assertThatThrownBy(() -> brokenQuote.query("000001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("quote down");

        var brokenDiscovery = service(code -> {
            throw new IllegalStateException("discovery down");
        }, security -> Optional.empty());
        assertThatThrownBy(() -> brokenDiscovery.query("000001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("discovery down");
    }

    /**
     * 基金代码为空或不是六位数字时，在访问供应商之前失败。
     */
    @Test
    void illegalFundCodeFailsBeforeProviders() {
        var service = service(code -> Optional.empty(), security -> Optional.empty());
        assertThatThrownBy(() -> service.query(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.query("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query("ABC")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query("00001")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 最大行情年龄缺失、为零或为负时，用例拒绝构造，避免把所有报价都标成同一种新鲜度。
     */
    @Test
    void nonPositiveQuoteAgeIsRejected() {
        assertThatThrownBy(() -> new RealtimeFundQuoteApplicationService(code -> Optional.empty(), security -> Optional.empty(), CLOCK, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxQuoteAge must be positive");
        assertThatThrownBy(() -> new RealtimeFundQuoteApplicationService(code -> Optional.empty(), security -> Optional.empty(), CLOCK, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxQuoteAge must be positive");
        assertThatThrownBy(() -> new RealtimeFundQuoteApplicationService(code -> Optional.empty(), security -> Optional.empty(), CLOCK, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxQuoteAge must be positive");
    }

    /**
     * 视图和结果拒绝互相矛盾的行情种类与状态。不可用状态不能夹带价格，可用状态不能没有价格。
     */
    @Test
    void inconsistentQuoteShapesAreRejected() {
        assertThatThrownBy(() -> new RealtimeFundQuoteView("000001", "159819", "人工智能ETF", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, NOW, MarketDataKind.OFFICIAL_FUND_NAV, "不是净值"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("realtime fund quote must be an UNDERLYING_ETF_PROXY");
        assertThatThrownBy(() -> new RealtimeFundQuoteResult(null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("status is required");
        assertThatThrownBy(() -> new RealtimeFundQuoteResult(RealtimeFundQuoteStatus.AVAILABLE, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quote is required for available data");
        assertThatThrownBy(() -> RealtimeFundQuoteResult.unavailable(RealtimeFundQuoteStatus.STALE, "过期"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quote is required for available data");

        var quote = new SourcedValue<>(proxyView(), new DataLineage(List.of(provenance(MarketDataKind.UNDERLYING_ETF_PROXY)), null));
        assertThatThrownBy(() -> new RealtimeFundQuoteResult(RealtimeFundQuoteStatus.DATA_NOT_READY, quote, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quote must be absent when data is unavailable");
        assertThatThrownBy(() -> new RealtimeFundQuoteResult(RealtimeFundQuoteStatus.NO_EXCHANGE_PROXY, quote, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quote must be absent when data is unavailable");
    }

    /**
     * 组装固定时钟和十分钟年龄的用例。发现器或报价器为空时构造会失败，测试始终传入非空实现。
     */
    private RealtimeFundQuoteApplicationService service(FundDiscoveryProvider discovery, MarketQuoteProvider quotes) {
        return new RealtimeFundQuoteApplicationService(discovery, quotes, CLOCK, Duration.ofMinutes(10));
    }

    /**
     * 构造一只固定的关联 ETF。基金代码不合法时由基金代码值对象拒绝。
     */
    private LinkedExchangeFund linked(FundCode fundCode) {
        return new LinkedExchangeFund(fundCode, new ExchangeSecurityCode("sz", "159819"), "人工智能ETF",
                provenance(MarketDataKind.UNDERLYING_ETF_PROXY));
    }

    /**
     * 构造一张种类正确的代理行情视图。传入其他行情种类时构造会失败。
     */
    private RealtimeFundQuoteView proxyView() {
        return new RealtimeFundQuoteView("000001", "159819", "人工智能ETF", new BigDecimal("1.23"), new BigDecimal("1.20"),
                new BigDecimal("0.03"), new BigDecimal("2.50"), NOW, MarketDataKind.UNDERLYING_ETF_PROXY, "仅供对照");
    }

    /**
     * 构造一条不含查询参数的来源说明。种类由调用方指定，来源地址不合法时由值对象拒绝。
     */
    private DataProvenance provenance(MarketDataKind kind) {
        return new DataProvenance(new ProviderId("quote-source"), URI.create("https://example.com/data"), kind, "v1", NOW, NOW,
                QualityStatus.VERIFIED, List.of());
    }
}
