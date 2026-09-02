package com.jijing.fund.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.model.*;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 天天基金公开页面数据适配器。仅供个人学习和演示，生产环境应替换为已授权的数据供应商。 */
@Component
@ConditionalOnProperty(prefix = "fund.provider", name = "type", havingValue = "eastmoney")
public class EastMoneyFundDataProvider implements ExternalFundDataProvider {
    private static final Pattern MANAGER_PATTERN = Pattern.compile("基金(?:及)?经理([^。<\\\"]+?)的信息");
    private final RestClient client; private final RestClient managerClient; private final ObjectMapper mapper; private final String deviceId; private final String mobileKey;
    private final Clock clock;

    public EastMoneyFundDataProvider(@Value("${fund.provider.base-url:https://fundmobapi.eastmoney.com}") String baseUrl, Clock clock,
            @Value("${fund.provider.device-id:656c09923c567b89bb44801020bc59ab||iemi_tluafed_me}") String deviceId,
            @Value("${fund.provider.mobile-key:656c09923c567b89bb44801020bc59ab||iemi_tluafed_me}") String mobileKey) {
        this.client = RestClient.builder().baseUrl(baseUrl).defaultHeader("User-Agent", "jijing-agent/0.1").build(); this.managerClient=RestClient.builder().baseUrl(baseUrl.contains("fundmobapi.eastmoney.com")?"https://fundf10.eastmoney.com":baseUrl).defaultHeader("User-Agent", "jijing-agent/0.1").build();
        this.mapper = new ObjectMapper(); this.clock = clock; this.deviceId=deviceId; this.mobileKey=mobileKey;
    }

    @Override public Optional<FundProfile> fetchProfile(FundCode code) {
        try {
            JsonNode data = tree(client.get().uri(uri -> uri.path("/FundMNewApi/FundMNNBasicInformation").queryParam("version", "6.2.4").queryParam("plat", "Android").queryParam("appType", "ttjj").queryParam("FCODE", code.value()).queryParam("onFundCache", 3).queryParam("keeeeeyparam", "FCODE").queryParam("deviceid", deviceId).queryParam("igggggnoreburst", true).queryParam("product", "EFund").queryParam("MobileKey", mobileKey).build()).retrieve().body(String.class)).path("Datas");
            String name = text(data, "SHORTNAME");
            if (name == null || name.isBlank()) return Optional.empty();
            Instant updatedAt = parseDate(text(data, "FSRQ")).orElse(clock.instant());
            return Optional.of(new FundProfile(code, name, text(data, "FTYPE"), text(data, "JJGS"), fetchManager(code), parseLocalDate(text(data, "ESTABDATE")).orElse(null), sourceName(), updatedAt, clock.instant()));
        } catch (RuntimeException ex) { throw unavailable("Unable to load fund profile", ex); }
    }

    private String fetchManager(FundCode code) { try { String html=managerClient.get().uri("/jjjl_"+code.value()+".html").retrieve().body(String.class); Matcher matcher=MANAGER_PATTERN.matcher(html==null?"":html); return matcher.find()?matcher.group(1).replace('&','、').trim():null; } catch (RuntimeException ignored) { return null; } }

    @Override public List<NavPoint> fetchNavHistory(FundCode code, LocalDate start, LocalDate end) {
        try {
            String body = client.get().uri(uri -> uri.path("/FundMNewApi/FundMNHisNetList").queryParam("FCODE", code.value()).queryParam("pageSize", 500).queryParam("pageIndex", 1).queryParam("plat", "Android").queryParam("version", "6.2.4").queryParam("appType", "ttjj").queryParam("product", "EFund").queryParam("deviceid", deviceId).queryParam("MobileKey", mobileKey).build()).retrieve().body(String.class);
            JsonNode rows = tree(body).path("Datas");
            Instant collected = clock.instant(); List<NavPoint> points = new ArrayList<>();
            for (JsonNode row : rows) {
                try { LocalDate date = LocalDate.parse(text(row, "FSRQ")); BigDecimal nav = new BigDecimal(text(row, "DWJZ"));
                    String accumulated = text(row, "LJJZ"); BigDecimal accumulatedNav = accumulated == null || accumulated.isBlank() || "--".equals(accumulated) ? nav : new BigDecimal(accumulated);
                    points.add(new NavPoint(code, date, nav, accumulatedNav, null, NavStatus.CONFIRMED, sourceName(), collected, collected));
                } catch (RuntimeException ignored) { /* 忽略供应商页面中的非数据行 */ }
            }
            return points.stream().filter(point -> !point.navDate().isBefore(start) && !point.navDate().isAfter(end)).toList();
        } catch (RuntimeException ex) { throw unavailable("Unable to load fund net value history", ex); }
    }

    @Override public String sourceName() { return "eastmoney-public"; }
    private JsonNode first(String body) { try { JsonNode rows = mapper.readTree(body).path("Datas"); return rows.isArray() && !rows.isEmpty() ? rows.get(0) : mapper.createObjectNode(); } catch (Exception ex) { throw unavailable("Invalid fund response", ex); } }
    private JsonNode tree(String body) { try { return mapper.readTree(body); } catch (Exception ex) { throw unavailable("Invalid fund response", ex); } }
    private String text(JsonNode data, String field) { JsonNode value = data.get(field); return value == null || value.isNull() ? null : value.asText(); }
    private Optional<Instant> parseDate(String value) { try { return value == null || value.isBlank() ? Optional.empty() : Optional.of(LocalDate.parse(value).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()); } catch (RuntimeException ignored) { return Optional.empty(); } }
    private Optional<LocalDate> parseLocalDate(String value) { try { return value == null || value.isBlank() || "--".equals(value) ? Optional.empty() : Optional.of(LocalDate.parse(value)); } catch (RuntimeException ignored) { return Optional.empty(); } }
    private ExternalDataSourceException unavailable(String message, Exception cause) { return new ExternalDataSourceException("DATA_SOURCE_UNAVAILABLE", message, cause); }
}
