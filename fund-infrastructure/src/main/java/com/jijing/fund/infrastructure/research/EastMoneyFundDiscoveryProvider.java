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
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** EastMoney adapter for resolving a fund's linked exchange-traded ETF. */
@Component
public final class EastMoneyFundDiscoveryProvider implements FundDiscoveryProvider {
    private static final ProviderId PROVIDER = new ProviderId("eastmoney-fund-api");
    private final RestClient client;
    private final ObjectMapper mapper;
    private final Clock clock;

    public EastMoneyFundDiscoveryProvider(@Value("${fund.research.providers.eastmoney.fund-base-url:https://fundmobapi.eastmoney.com}") String baseUrl,
                                          ObjectMapper mapper, Clock clock) {
        this.client = RestClient.builder().baseUrl(baseUrl).defaultHeader("User-Agent", "FundPilot/2.0").build();
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Optional<LinkedExchangeFund> findLinkedExchangeFund(FundCode fundCode) {
        try {
            String body = client.get().uri(uri -> uri.path("/FundMNewApi/FundMNInverstPosition")
                    .queryParam("FCODE", fundCode.value()).queryParam("deviceid", "Wap").queryParam("plat", "Wap")
                    .queryParam("product", "EFund").queryParam("version", "2.0.0").build()).retrieve().body(String.class);
            JsonNode data = mapper.readTree(body).path("Datas");
            String code = data.path("ETFCODE").asText();
            if (!code.matches("[15]\\d{5}")) return Optional.empty();
            String name = data.path("ETFSHORTNAME").asText(code);
            Instant now = clock.instant();
            var provenance = new DataProvenance(PROVIDER, URI.create("https://fundmobapi.eastmoney.com/FundMNewApi/FundMNInverstPosition"),
                    MarketDataKind.UNDERLYING_ETF_PROXY, "fund-link-" + fundCode.value(), null, now,
                    QualityStatus.VERIFIED, java.util.List.of());
            return Optional.of(new LinkedExchangeFund(fundCode, new ExchangeSecurityCode(code.startsWith("5") ? "sh" : "sz", code), name, provenance));
        } catch (Exception error) {
            throw new ExternalDataSourceException("FUND_DISCOVERY_UNAVAILABLE", "Unable to resolve linked exchange fund", error);
        }
    }
}
