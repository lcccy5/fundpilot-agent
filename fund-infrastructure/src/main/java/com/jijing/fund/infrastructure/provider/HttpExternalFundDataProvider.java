package com.jijing.fund.infrastructure.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.FundProfile;
import com.jijing.fund.domain.model.NavPoint;
import com.jijing.fund.domain.model.NavStatus;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 按 docs/provider-http-contract.md 调用已配置的基金 HTTP API。
 * 连接超时默认 2 秒，读超时默认 5 秒。连接失败与读超时都表现为 {@link ResourceAccessException}，
 * 本类不区分二者，一律抛出 {@code DATA_SOURCE_UNAVAILABLE}，文案为请求超时。
 * 档案或净值响应体为 null，或净值条目为 null，抛出 {@code DATA_QUALITY_ERROR}。
 * 没有重复提交去重；同一代码的重复调用会再次访问远端。
 * {@code @Retry}、熔断和限流只在 Spring 代理上生效，直接 new 出来的实例不会重试。
 */
@Component
@ConditionalOnProperty(prefix = "fund.provider", name = "type", havingValue = "real")
public class HttpExternalFundDataProvider implements ExternalFundDataProvider {
    private final RestClient client;
    private final Clock clock;
    private final String sourceName;

    /**
     * 用 JDK HttpClient 固定连接超时，并用请求工厂固定读超时。密钥为空时不发送 Authorization。
     */
    public HttpExternalFundDataProvider(@Value("${fund.provider.base-url}") String baseUrl,
            @Value("${fund.provider.api-key:}") String apiKey,
            @Value("${fund.provider.source-name:configured-http-api}") String sourceName, Clock clock,
            @Value("${fund.provider.connect-timeout:2s}") Duration connectTimeout,
            @Value("${fund.provider.read-timeout:5s}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, "jijing-agent/0.1");
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        builder.messageConverters(converters -> {
            converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
            converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
        });
        if (!apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.client = builder.build();
        this.clock = clock;
        this.sourceName = sourceName;
    }

    /**
     * 404 视为基金不存在并返回空。其他 HTTP 状态按 {@link #statusCode} 映射。
     * 响应体缺失，或名称为空白，视为数据质量错误，不返回残缺档案。
     */
    @Override
    @Retry(name = "fundProvider")
    @CircuitBreaker(name = "fundProvider")
    @RateLimiter(name = "fundProvider")
    public Optional<FundProfile> fetchProfile(FundCode code) {
        try {
            ProfileResponse response = client.get().uri("/funds/{code}", code.value()).retrieve()
                    .body(ProfileResponse.class);
            if (response == null) {
                throw quality("Empty profile response");
            }
            return Optional.of(new FundProfile(code, required(response.name(), "name"), response.fundType(),
                    response.managementCompany(), response.fundManager(), response.establishedDate(), sourceName,
                    response.sourceUpdatedAt(), clock.instant()));
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (RestClientResponseException ex) {
            throw new ExternalDataSourceException(statusCode(ex),
                    "Fund provider returned HTTP " + ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw new ExternalDataSourceException("DATA_SOURCE_UNAVAILABLE", "Fund provider request timed out", ex);
        }
    }

    /**
     * 净值历史没有单独的 404 分支，HTTP 错误都按状态码抛出。
     * 响应体或 items 为 null 时抛出数据质量错误；items 为空列表则返回空列表。
     */
    @Override
    @Retry(name = "fundProvider")
    @CircuitBreaker(name = "fundProvider")
    @RateLimiter(name = "fundProvider")
    public List<NavPoint> fetchNavHistory(FundCode code, LocalDate start, LocalDate end) {
        try {
            NavHistoryResponse response = client.get().uri(uri -> uri.path("/funds/{code}/nav")
                    .queryParam("startDate", start).queryParam("endDate", end).build(code.value()))
                    .retrieve().body(NavHistoryResponse.class);
            if (response == null || response.items() == null) {
                throw quality("Empty nav response");
            }
            Instant collectedAt = clock.instant();
            return response.items().stream().map(item -> new NavPoint(code, item.navDate(), item.unitNav(),
                    item.accumulatedNav(), null, NavStatus.CONFIRMED, sourceName, item.sourceUpdatedAt(), collectedAt))
                    .toList();
        } catch (RestClientResponseException ex) {
            throw new ExternalDataSourceException(statusCode(ex),
                    "Fund provider returned HTTP " + ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw new ExternalDataSourceException("DATA_SOURCE_UNAVAILABLE", "Fund provider request timed out", ex);
        }
    }

    /** 配置的来源名，写入档案与净值，不参与 URL。 */
    @Override
    public String sourceName() {
        return sourceName;
    }

    /** 空白字段不能进入领域对象，避免把缺字段伪装成有效档案。 */
    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw quality("Missing field: " + field);
        }
        return value;
    }

    /** 供应商返回了可解析但不可用的内容。 */
    private ExternalDataSourceException quality(String message) {
        return new ExternalDataSourceException("DATA_QUALITY_ERROR", message);
    }

    /** 429 单独标成限流，其余 HTTP 失败与连接类错误使用同一不可用码。 */
    private String statusCode(RestClientResponseException ex) {
        return ex.getStatusCode().value() == 429 ? "DATA_SOURCE_RATE_LIMITED" : "DATA_SOURCE_UNAVAILABLE";
    }

    /** 契约中的基金详情 JSON。字段缺失时由调用方决定是拒绝还是原样传入。 */
    record ProfileResponse(String code, String name, String fundType, String managementCompany, String fundManager,
            LocalDate establishedDate, Instant sourceUpdatedAt) {
    }

    /** 契约中的净值列表。items 为 null 与响应体缺失同样拒绝。 */
    record NavHistoryResponse(List<NavResponse> items) {
    }

    /** 单条净值。复权净值不在该契约中，调用方固定传 null。 */
    record NavResponse(LocalDate navDate, BigDecimal unitNav, BigDecimal accumulatedNav, Instant sourceUpdatedAt) {
    }
}
