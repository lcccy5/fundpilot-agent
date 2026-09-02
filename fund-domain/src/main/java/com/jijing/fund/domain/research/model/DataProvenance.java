package com.jijing.fund.domain.research.model;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Auditable, secret-free description of where one input value came from. */
public record DataProvenance(
        ProviderId providerId,
        URI sourceUri,
        MarketDataKind dataKind,
        String sourceVersion,
        Instant sourceUpdatedAt,
        Instant collectedAt,
        QualityStatus qualityStatus,
        List<String> qualityWarnings) {

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
