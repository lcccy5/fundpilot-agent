package com.jijing.fund.infrastructure.provider;

import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jijing.fund.domain.model.*;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

/**
 * 面向标准基金数据 API 的 HTTP Adapter。外部服务契约见 docs/provider-http-contract.md。
 */
@Component
@ConditionalOnProperty(prefix="fund.provider", name="type", havingValue="real")
public class HttpExternalFundDataProvider implements ExternalFundDataProvider {
    private final RestClient client; private final Clock clock; private final String sourceName;
    public HttpExternalFundDataProvider(@Value("${fund.provider.base-url}") String baseUrl,
            @Value("${fund.provider.api-key:}") String apiKey,
            @Value("${fund.provider.source-name:configured-http-api}") String sourceName, Clock clock,
            @Value("${fund.provider.connect-timeout:2s}") Duration connectTimeout,
            @Value("${fund.provider.read-timeout:5s}") Duration readTimeout) {
        var httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient); requestFactory.setReadTimeout(readTimeout);
        var builder = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, "jijing-agent/0.1");
        var objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        builder.messageConverters(converters -> {
            converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
            converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
        });
        if (!apiKey.isBlank()) builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        this.client = builder.build(); this.clock = clock; this.sourceName = sourceName;
    }

    @Override @Retry(name="fundProvider") @CircuitBreaker(name="fundProvider") @RateLimiter(name="fundProvider")
    public Optional<FundProfile> fetchProfile(FundCode code) {
        try {
            ProfileResponse response = client.get().uri("/funds/{code}", code.value()).retrieve().body(ProfileResponse.class);
            if (response == null) throw quality("Empty profile response");
            return Optional.of(new FundProfile(code, required(response.name(), "name"), response.fundType(),
                    response.managementCompany(), response.fundManager(), response.establishedDate(), sourceName,
                    response.sourceUpdatedAt(), clock.instant()));
        } catch (HttpClientErrorException.NotFound ex) { return Optional.empty(); }
        catch (RestClientResponseException ex) { throw new ExternalDataSourceException(statusCode(ex), "Fund provider returned HTTP " + ex.getStatusCode().value(), ex); }
        catch (ResourceAccessException ex) { throw new ExternalDataSourceException("DATA_SOURCE_UNAVAILABLE", "Fund provider request timed out", ex); }
    }

    @Override @Retry(name="fundProvider") @CircuitBreaker(name="fundProvider") @RateLimiter(name="fundProvider")
    public List<NavPoint> fetchNavHistory(FundCode code, LocalDate start, LocalDate end) {
        try {
            NavHistoryResponse response = client.get().uri(uri -> uri.path("/funds/{code}/nav")
                    .queryParam("startDate", start).queryParam("endDate", end).build(code.value())).retrieve().body(NavHistoryResponse.class);
            if (response == null || response.items() == null) throw quality("Empty nav response");
            Instant collectedAt = clock.instant();
            return response.items().stream().map(item -> new NavPoint(code, item.navDate(), item.unitNav(), item.accumulatedNav(),
                    null, NavStatus.CONFIRMED, sourceName, item.sourceUpdatedAt(), collectedAt)).toList();
        } catch (RestClientResponseException ex) { throw new ExternalDataSourceException(statusCode(ex), "Fund provider returned HTTP " + ex.getStatusCode().value(), ex); }
        catch (ResourceAccessException ex) { throw new ExternalDataSourceException("DATA_SOURCE_UNAVAILABLE", "Fund provider request timed out", ex); }
    }
    @Override public String sourceName() { return sourceName; }
    private String required(String value, String field) { if (value == null || value.isBlank()) throw quality("Missing field: " + field); return value; }
    private ExternalDataSourceException quality(String message) { return new ExternalDataSourceException("DATA_QUALITY_ERROR", message); }
    private String statusCode(RestClientResponseException ex) { return ex.getStatusCode().value() == 429 ? "DATA_SOURCE_RATE_LIMITED" : "DATA_SOURCE_UNAVAILABLE"; }
    record ProfileResponse(String code, String name, String fundType, String managementCompany, String fundManager,
                           LocalDate establishedDate, Instant sourceUpdatedAt) {}
    record NavHistoryResponse(List<NavResponse> items) {}
    record NavResponse(LocalDate navDate, BigDecimal unitNav, BigDecimal accumulatedNav, Instant sourceUpdatedAt) {}
}
