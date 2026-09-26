package com.jijing.fund.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.FundProfile;
import com.jijing.fund.domain.model.NavPoint;
import com.jijing.fund.domain.model.NavStatus;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 从天天基金公开页面拼装档案和历史净值，仅供个人学习和演示。
 * RestClient 没有设置连接或读超时，远端挂起会一直占用调用线程。
 * 连接失败、HTTP 错误和无法解析的正文都包成 {@code DATA_SOURCE_UNAVAILABLE}。
 * 档案里简称缺失或空白时返回空，不抛异常；净值里的坏行被跳过，整页无数据则返回空列表。
 * 经理页失败被吞掉，档案仍返回且经理为 null。没有重复提交去重。
 */
@Component
@ConditionalOnProperty(prefix = "fund.provider", name = "type", havingValue = "eastmoney")
public class EastMoneyFundDataProvider implements ExternalFundDataProvider {
    private static final Pattern MANAGER_PATTERN = Pattern.compile("基金(?:及)?经理([^。<\\\"]+?)的信息");
    private final RestClient client;
    private final RestClient managerClient;
    private final ObjectMapper mapper;
    private final String deviceId;
    private final String mobileKey;
    private final Clock clock;

    /**
     * 基础地址指向官方移动站时，经理页改走 fundf10；否则两个客户端共用同一基址，便于测试桩。
     */
    public EastMoneyFundDataProvider(
            @Value("${fund.provider.base-url:https://fundmobapi.eastmoney.com}") String baseUrl, Clock clock,
            @Value("${fund.provider.device-id:656c09923c567b89bb44801020bc59ab||iemi_tluafed_me}") String deviceId,
            @Value("${fund.provider.mobile-key:656c09923c567b89bb44801020bc59ab||iemi_tluafed_me}") String mobileKey) {
        this.client = RestClient.builder().baseUrl(baseUrl).defaultHeader("User-Agent", "jijing-agent/0.1").build();
        String managerBase = baseUrl.contains("fundmobapi.eastmoney.com") ? "https://fundf10.eastmoney.com" : baseUrl;
        this.managerClient = RestClient.builder().baseUrl(managerBase)
                .defaultHeader("User-Agent", "jijing-agent/0.1").build();
        this.mapper = new ObjectMapper();
        this.clock = clock;
        this.deviceId = deviceId;
        this.mobileKey = mobileKey;
    }

    /**
     * 简称缺失视为未找到。日期无法解析时，来源更新时间退回调用时刻，成立日留空。
     */
    @Override
    public Optional<FundProfile> fetchProfile(FundCode code) {
        try {
            JsonNode data = tree(client.get().uri(uri -> uri.path("/FundMNewApi/FundMNNBasicInformation")
                    .queryParam("version", "6.2.4")
                    .queryParam("plat", "Android")
                    .queryParam("appType", "ttjj")
                    .queryParam("FCODE", code.value())
                    .queryParam("onFundCache", 3)
                    .queryParam("keeeeeyparam", "FCODE")
                    .queryParam("deviceid", deviceId)
                    .queryParam("igggggnoreburst", true)
                    .queryParam("product", "EFund")
                    .queryParam("MobileKey", mobileKey)
                    .build()).retrieve().body(String.class)).path("Datas");
            String name = text(data, "SHORTNAME");
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            Instant updatedAt = parseDate(text(data, "FSRQ")).orElse(clock.instant());
            return Optional.of(new FundProfile(code, name, text(data, "FTYPE"), text(data, "JJGS"), fetchManager(code),
                    parseLocalDate(text(data, "ESTABDATE")).orElse(null), sourceName(), updatedAt, clock.instant()));
        } catch (RuntimeException ex) {
            throw unavailable("Unable to load fund profile", ex);
        }
    }

    /**
     * 经理页超时、断连或正文不含固定句式时返回 null，不让附属页面拖垮档案查询。
     */
    private String fetchManager(FundCode code) {
        try {
            String html = managerClient.get().uri("/jjjl_" + code.value() + ".html").retrieve().body(String.class);
            Matcher matcher = MANAGER_PATTERN.matcher(html == null ? "" : html);
            if (!matcher.find()) {
                return null;
            }
            return matcher.group(1).replace('&', '、').trim();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 只保留起止日期内、且日期和单位净值都能解析的行。累计净值缺失或为 {@code --} 时退回单位净值。
     */
    @Override
    public List<NavPoint> fetchNavHistory(FundCode code, LocalDate start, LocalDate end) {
        try {
            String body = client.get().uri(uri -> uri.path("/FundMNewApi/FundMNHisNetList")
                    .queryParam("FCODE", code.value())
                    .queryParam("pageSize", 500)
                    .queryParam("pageIndex", 1)
                    .queryParam("plat", "Android")
                    .queryParam("version", "6.2.4")
                    .queryParam("appType", "ttjj")
                    .queryParam("product", "EFund")
                    .queryParam("deviceid", deviceId)
                    .queryParam("MobileKey", mobileKey)
                    .build()).retrieve().body(String.class);
            JsonNode rows = tree(body).path("Datas");
            Instant collected = clock.instant();
            List<NavPoint> points = new ArrayList<>();
            for (JsonNode row : rows) {
                try {
                    LocalDate date = LocalDate.parse(text(row, "FSRQ"));
                    BigDecimal nav = new BigDecimal(text(row, "DWJZ"));
                    String accumulated = text(row, "LJJZ");
                    BigDecimal accumulatedNav = accumulated == null || accumulated.isBlank() || "--".equals(accumulated)
                            ? nav : new BigDecimal(accumulated);
                    points.add(new NavPoint(code, date, nav, accumulatedNav, null, NavStatus.CONFIRMED, sourceName(),
                            collected, collected));
                } catch (RuntimeException ignored) {
                    // 页面夹杂说明行时跳过该行，避免整段历史失败。
                }
            }
            return points.stream()
                    .filter(point -> !point.navDate().isBefore(start) && !point.navDate().isAfter(end))
                    .toList();
        } catch (RuntimeException ex) {
            throw unavailable("Unable to load fund net value history", ex);
        }
    }

    /** 写入领域对象的固定来源标识。 */
    @Override
    public String sourceName() {
        return "eastmoney-public";
    }

    /**
     * 取 Datas 数组的首行；空数组或非数组时返回空对象。当前调用链未使用。
     */
    private JsonNode first(String body) {
        try {
            JsonNode rows = mapper.readTree(body).path("Datas");
            return rows.isArray() && !rows.isEmpty() ? rows.get(0) : mapper.createObjectNode();
        } catch (Exception ex) {
            throw unavailable("Invalid fund response", ex);
        }
    }

    /** 正文不是 JSON 时与断连一样视为数据源不可用。 */
    private JsonNode tree(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception ex) {
            throw unavailable("Invalid fund response", ex);
        }
    }

    /** 缺字段或 JSON null 都返回 null，交给调用方决定是拒绝还是跳过。 */
    private String text(JsonNode data, String field) {
        JsonNode value = data.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 按上海时区把 yyyy-MM-dd 转成当天零点；空白或非法格式返回空。 */
    private Optional<Instant> parseDate(String value) {
        try {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(LocalDate.parse(value).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /** {@code --} 与空白都表示页面没有给出日期。 */
    private Optional<LocalDate> parseLocalDate(String value) {
        try {
            if (value == null || value.isBlank() || "--".equals(value)) {
                return Optional.empty();
            }
            return Optional.of(LocalDate.parse(value));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /** 把供应商异常收成稳定错误码，原因保留在 cause 中。 */
    private ExternalDataSourceException unavailable(String message, Exception cause) {
        return new ExternalDataSourceException("DATA_SOURCE_UNAVAILABLE", message, cause);
    }
}
