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

/**
 * 腾讯行情文本协议适配器。供应商原文不离开基础设施层。
 * RestClient 未设置连接或读超时，挂起没有上限。
 * 响应体为 null 时返回空；正文缺少引号、字段不够或价格、时间非法时抛出 {@code PROVIDER_CONTRACT_ERROR}。
 * 连接失败和其他未分类异常抛出 {@code MARKET_QUOTE_UNAVAILABLE}。没有重复提交去重。
 * 涨跌额、涨跌幅缺失时对应字段为 null，不视为契约错误。
 */
@Component
public final class TencentMarketQuoteProvider implements MarketQuoteProvider {
    private static final ProviderId PROVIDER = new ProviderId("tencent-quote");
    private static final DateTimeFormatter QUOTE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private final RestClient client;
    private final Clock clock;

    /** 行情基址默认 qt.gtimg.cn，测试可替换为本地桩。 */
    public TencentMarketQuoteProvider(
            @Value("${fund.research.providers.tencent.quote-base-url:https://qt.gtimg.cn}") String baseUrl,
            Clock clock) {
        this.client = RestClient.builder().baseUrl(baseUrl).defaultHeader("User-Agent", "FundPilot/2.0").build();
        this.clock = clock;
    }

    /**
     * 只解析引号内以 {@code ~} 分隔的字段。名称、现价和时间是必填；昨收、涨跌额、涨跌幅可空。
     */
    @Override
    public Optional<SecurityQuote> latestQuote(ExchangeSecurityCode securityCode) {
        try {
            String body = client.get().uri(uri -> uri.queryParam("q", securityCode.providerSymbol()).build())
                    .retrieve().body(String.class);
            if (body == null) {
                return Optional.empty();
            }
            int start = body.indexOf('"');
            int end = body.lastIndexOf('"');
            if (start < 0 || end <= start) {
                throw contract("Tencent quote response has no data fields");
            }
            String[] fields = body.substring(start + 1, end).split("~", -1);
            if (fields.length <= 32 || fields[1].isBlank() || fields[3].isBlank()) {
                throw contract("Tencent quote response is missing required fields");
            }
            String version = fields[30];
            Instant quoteTime = parseTime(version);
            Instant now = clock.instant();
            DataProvenance provenance = new DataProvenance(PROVIDER, URI.create("https://qt.gtimg.cn"),
                    MarketDataKind.EXCHANGE_TRADED_QUOTE, version, quoteTime, now, QualityStatus.VERIFIED, List.of());
            return Optional.of(new SecurityQuote(securityCode, fields[1], decimal(fields[3]), decimalOrNull(fields[4]),
                    decimalOrNull(fields[31]), decimalOrNull(fields[32]), quoteTime, provenance));
        } catch (ExternalDataSourceException error) {
            throw error;
        } catch (Exception error) {
            throw new ExternalDataSourceException("MARKET_QUOTE_UNAVAILABLE", "Unable to load market quote", error);
        }
    }

    /** 行情时间按上海时区解释；格式不符属于契约错误，而不是连接失败。 */
    private Instant parseTime(String value) {
        try {
            return LocalDateTime.parse(value, QUOTE_TIME).atZone(CHINA).toInstant();
        } catch (RuntimeException error) {
            throw contract("Tencent quote timestamp is invalid");
        }
    }

    /** 现价必须是数字，否则整条行情不可用。 */
    private BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (RuntimeException error) {
            throw contract("Tencent quote price is invalid");
        }
    }

    /** 可选价格字段空白或非法时留空，避免昨收缺失拖垮现价。 */
    private BigDecimal decimalOrNull(String value) {
        try {
            return value == null || value.isBlank() ? null : new BigDecimal(value);
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** 正文能到达但形状不对。 */
    private ExternalDataSourceException contract(String message) {
        return new ExternalDataSourceException("PROVIDER_CONTRACT_ERROR", message);
    }
}
