package com.jijing.fund.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 基金基础档案，包括名称、类型、管理人、基金经理、成立日期，以及数据来源、来源更新时间和采集时间，
 * 用于展示和追溯数据出处。
 */
public record FundProfile(
        FundCode code,
        String name,
        String fundType,
        String managementCompany,
        String fundManager,
        LocalDate establishedDate,
        String dataSource,
        Instant sourceUpdatedAt,
        Instant collectedAt) {
    /**
     * 校验档案必填项。
     * code、name、dataSource、collectedAt 为 null 时抛出 NullPointerException；name 为空白时抛出 IllegalArgumentException。
     * 类型、管理人、基金经理、成立日期和来源更新时间允许为 null，dataSource 允许为空字符串。
     */
    public FundProfile {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) throw new IllegalArgumentException("fund name must not be blank");
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        Objects.requireNonNull(collectedAt, "collectedAt must not be null");
    }
}
