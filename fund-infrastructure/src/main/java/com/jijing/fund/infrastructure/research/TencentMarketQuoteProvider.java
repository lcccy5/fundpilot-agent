package com.jijing.fund.infrastructure.research;

import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.research.model.DataProvenance;
import com.jijing.fund.domain.research.model.ExchangeSecurityCode;
import com.jijing.fund.domain.research.model.MarketDataKind;
import com.jijing.fund.domain.research.model.ProviderId;
import com.jijing.fund.domain.research.model.QualityStatus;
import com.jijing.fund.domain.research.model.SecurityQuote;
import com.jijing.fund.domain.research.provider.MarketQuoteProvider;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Tencent quote protocol adapter. The provider response never leaves Infrastructure. */
@Component
public final class TencentMarketQuoteProvider implements MarketQuoteProvider {
    private static final ProviderId PROVIDER = new ProviderId("tencent-quote");
    private static final DateTimeFormatter QUOTE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private final RestClient client;
    private final Clock clock;

    public TencentMarketQuoteProvider(@Value("${fund.research.providers.tencent.quote-base-url:https://qt.gtimg.cn}") String baseUrl,
                                      Clock clock) {
        this.client = RestClient.builder().baseUrl(baseUrl).defaultHeader("User-Agent", "FundPilot/2.0").build();
        this.clock = clock;
    }

    @Override
    public Optional<SecurityQuote> latestQuote(ExchangeSecurityCode securityCode) {
        try {
            String body = client.get().uri(uri -> uri.queryParam("q", securityCode.providerSymbol()).build()).retrieve().body(String.class);
            if (body == null) return Optional.empty();
            int start = body.indexOf('"');
            int end = body.lastIndexOf('"');
            if (start < 0 || end <= start) throw contract("Tencent quote response has no data fields");
            String[] fields = body.substring(start + 1, end).split("~", -1);
            if (fields.length <= 32 || fields[1].isBlank() || fields[3].isBlank()) throw contract("Tencent quote response is missing required fields");
            String version = fields[30];
            Instant quoteTime = parseTime(version);
            Instant now = clock.instant();
            var provenance = new DataProvenance(PROVIDER, URI.create("https://qt.gtimg.cn"), MarketDataKind.EXCHANGE_TRADED_QUOTE,
                    version, quoteTime, now, QualityStatus.VERIFIED, List.of());
            return Optional.of(new SecurityQuote(securityCode, fields[1], decimal(fields[3]), decimalOrNull(fields[4]),
                    decimalOrNull(fields[31]), decimalOrNull(fields[32]), quoteTime, provenance));
        } catch (ExternalDataSourceException error) {
            throw error;
        } catch (Exception error) {
            throw new ExternalDataSourceException("MARKET_QUOTE_UNAVAILABLE", "Unable to load market quote", error);
        }
    }

    private Instant parseTime(String value) {
        try { return LocalDateTime.parse(value, QUOTE_TIME).atZone(CHINA).toInstant(); }
        catch (RuntimeException error) { throw contract("Tencent quote timestamp is invalid"); }
    }
    private BigDecimal decimal(String value) { try { return new BigDecimal(value); } catch (RuntimeException error) { throw contract("Tencent quote price is invalid"); } }
    private BigDecimal decimalOrNull(String value) { try { return value == null || value.isBlank() ? null : new BigDecimal(value); } catch (RuntimeException error) { return null; } }
    private ExternalDataSourceException contract(String message) { return new ExternalDataSourceException("PROVIDER_CONTRACT_ERROR", message); }
}
