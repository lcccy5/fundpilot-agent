package com.jijing.fund.domain.research.model;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 单个输入值的来源说明，可审计且不含任何密钥：数据提供方、来源地址、数据含义、来源版本、
 * 来源更新时间与采集时间，以及数据质量状态和质量告警。
 */
public record DataProvenance(
        ProviderId providerId,
        URI sourceUri,
        MarketDataKind dataKind,
        String sourceVersion,
        Instant sourceUpdatedAt,
        Instant collectedAt,
        QualityStatus qualityStatus,
        List<String> qualityWarnings) {

    /**
     * 校验来源并规范化可选字段。
     * providerId、sourceUri、dataKind、collectedAt、qualityStatus 为 null 时抛出 NullPointerException；
     * sourceUri 不是 http/https、没有主机名、带用户信息、带查询参数或片段时抛出 IllegalArgumentException，以防密钥随地址泄露。
     * sourceVersion 为 null 或空白时记为 "unknown"，否则去除首尾空白；质量告警去除 null、去除首尾空白、丢弃空串并去重，
     * 结果为不可变列表。sourceUpdatedAt 允许为 null。
     */
    public DataProvenance {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        Objects.requireNonNull(dataKind, "dataKind must not be null");
        Objects.requireNonNull(collectedAt, "collectedAt must not be null");
        Objects.requireNonNull(qualityStatus, "qualityStatus must not be null");
        if (!"https".equalsIgnoreCase(sourceUri.getScheme()) && !"http".equalsIgnoreCase(sourceUri.getScheme())) {
            throw new IllegalArgumentException("sourceUri must use http or https");
        }
        if (sourceUri.getHost() == null || sourceUri.getUserInfo() != null) {
            throw new IllegalArgumentException("sourceUri must have a host and no user info");
        }
        if (sourceUri.getQuery() != null || sourceUri.getFragment() != null) {
            throw new IllegalArgumentException("sourceUri must not contain query parameters or fragments");
        }
        sourceVersion = sourceVersion == null || sourceVersion.isBlank() ? "unknown" : sourceVersion.trim();
        qualityWarnings = qualityWarnings == null ? List.of() : qualityWarnings.stream()
                .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }
}
