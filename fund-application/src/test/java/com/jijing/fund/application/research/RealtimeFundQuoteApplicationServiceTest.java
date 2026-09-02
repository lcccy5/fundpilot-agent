package com.jijing.fund.application.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.research.model.DataProvenance;
import com.jijing.fund.domain.research.model.ExchangeSecurityCode;
import com.jijing.fund.domain.research.model.LinkedExchangeFund;
import com.jijing.fund.domain.research.model.MarketDataKind;
import com.jijing.fund.domain.research.model.ProviderId;
import com.jijing.fund.domain.research.model.QualityStatus;
import com.jijing.fund.domain.research.model.SecurityQuote;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RealtimeFundQuoteApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-27T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void quoteIsAlwaysMarkedAsUnderlyingEtfProxy() {
        var service = new RealtimeFundQuoteApplicationService(code -> Optional.of(linked(code)), security -> Optional.of(quote(NOW.minusSeconds(30))),
                CLOCK, Duration.ofMinutes(10));

        var result = service.query("000001");

        assertThat(result.status()).isEqualTo(RealtimeFundQuoteStatus.AVAILABLE);
        assertThat(result.quote().value().dataKind()).isEqualTo(MarketDataKind.UNDERLYING_ETF_PROXY);
        assertThat(result.quote().lineage().inputs()).extracting(DataProvenance::dataKind)
                .containsExactly(MarketDataKind.UNDERLYING_ETF_PROXY, MarketDataKind.EXCHANGE_TRADED_QUOTE);
    }

    @Test
    void staleQuoteIsReturnedWithExplicitStatus() {
        var service = new RealtimeFundQuoteApplicationService(code -> Optional.of(linked(code)), security -> Optional.of(quote(NOW.minus(Duration.ofMinutes(11)))),
                CLOCK, Duration.ofMinutes(10));

        var result = service.query("000001");

        assertThat(result.status()).isEqualTo(RealtimeFundQuoteStatus.STALE);
        assertThat(result.limitations()).contains("QUOTE_STALE");
    }

    @Test
    void missingLinkedEtfIsBusinessStatusNotSystemFailure() {
        var service = new RealtimeFundQuoteApplicationService(code -> Optional.empty(), security -> Optional.empty(), CLOCK, Duration.ofMinutes(10));

        var result = service.query("000001");

        assertThat(result.status()).isEqualTo(RealtimeFundQuoteStatus.NO_EXCHANGE_PROXY);
        assertThat(result.quote()).isNull();
    }

    private LinkedExchangeFund linked(FundCode fundCode) {
        return new LinkedExchangeFund(fundCode, new ExchangeSecurityCode("sz", "159819"), "人工智能ETF",
                provenance("eastmoney-fund-api", MarketDataKind.UNDERLYING_ETF_PROXY, NOW));
    }

    private SecurityQuote quote(Instant quoteTime) {
        return new SecurityQuote(new ExchangeSecurityCode("sz", "159819"), "人工智能ETF", new BigDecimal("1.23"),
                new BigDecimal("1.20"), new BigDecimal("0.03"), new BigDecimal("2.50"), quoteTime,
                provenance("tencent-quote", MarketDataKind.EXCHANGE_TRADED_QUOTE, quoteTime));
    }

    private DataProvenance provenance(String provider, MarketDataKind kind, Instant updatedAt) {
        return new DataProvenance(new ProviderId(provider), URI.create("https://example.com/data"), kind, "v1", updatedAt,
                NOW, QualityStatus.VERIFIED, List.of());
    }
}
