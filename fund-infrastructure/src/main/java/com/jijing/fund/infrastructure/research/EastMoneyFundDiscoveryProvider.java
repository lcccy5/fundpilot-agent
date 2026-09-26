package com.jijing.fund.infrastructure.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.research.model.DataProvenance;
import com.jijing.fund.domain.research.model.ExchangeSecurityCode;
import com.jijing.fund.domain.research.model.LinkedExchangeFund;
import com.jijing.fund.domain.research.model.MarketDataKind;
import com.jijing.fund.domain.research.model.ProviderId;
import com.jijing.fund.domain.research.model.QualityStatus;
import com.jijing.fund.domain.research.provider.FundDiscoveryProvider;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 用天天基金持仓接口判断一只基金是否关联场内 ETF。
 * RestClient 未设置连接或读超时。
 * 连接失败、空正文、非法 JSON 都抛出 {@code FUND_DISCOVERY_UNAVAILABLE}。
 * ETF 代码不是 1 或 5 开头的六位数字时返回空，包括字段缺失。没有重复提交去重。
 */
@Component
public final class EastMoneyFundDiscoveryProvider implements FundDiscoveryProvider {
    private static final ProviderId PROVIDER = new ProviderId("eastmoney-fund-api");
    private final RestClient client;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** 基址可替换；JSON 解析使用应用里的 ObjectMapper，以保持日期模块一致。 */
    public EastMoneyFundDiscoveryProvider(
            @Value("${fund.research.providers.eastmoney.fund-base-url:https://fundmobapi.eastmoney.com}") String baseUrl,
            ObjectMapper mapper, Clock clock) {
        this.client = RestClient.builder().baseUrl(baseUrl).defaultHeader("User-Agent", "FundPilot/2.0").build();
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 5 开头映射上交所，其余合法代码映射深交所。名称缺失时用代码占位。
     */
    @Override
    public Optional<LinkedExchangeFund> findLinkedExchangeFund(FundCode fundCode) {
        try {
            String body = client.get().uri(uri -> uri.path("/FundMNewApi/FundMNInverstPosition")
                    .queryParam("FCODE", fundCode.value())
                    .queryParam("deviceid", "Wap")
                    .queryParam("plat", "Wap")
                    .queryParam("product", "EFund")
                    .queryParam("version", "2.0.0")
                    .build()).retrieve().body(String.class);
            JsonNode data = mapper.readTree(body).path("Datas");
            String code = data.path("ETFCODE").asText();
            if (!code.matches("[15]\\d{5}")) {
                return Optional.empty();
            }
            String name = data.path("ETFSHORTNAME").asText(code);
            Instant now = clock.instant();
            DataProvenance provenance = new DataProvenance(PROVIDER,
                    URI.create("https://fundmobapi.eastmoney.com/FundMNewApi/FundMNInverstPosition"),
                    MarketDataKind.UNDERLYING_ETF_PROXY, "fund-link-" + fundCode.value(), null, now,
                    QualityStatus.VERIFIED, List.of());
            String exchange = code.startsWith("5") ? "sh" : "sz";
            return Optional.of(new LinkedExchangeFund(fundCode, new ExchangeSecurityCode(exchange, code), name,
                    provenance));
        } catch (Exception error) {
            throw new ExternalDataSourceException("FUND_DISCOVERY_UNAVAILABLE",
                    "Unable to resolve linked exchange fund", error);
        }
    }
}
