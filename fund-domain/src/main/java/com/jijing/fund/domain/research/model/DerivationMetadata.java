package com.jijing.fund.domain.research.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 由来源输入经确定性计算得到的结果的推导说明：算法版本、规则版本、计算时间和已知局限。 */
public record DerivationMetadata(String algorithmVersion, String ruleVersion, Instant calculatedAt,
                                 List<String> limitations) {
    /**
     * 校验版本信息并规范化局限说明。
     * algorithmVersion 或 ruleVersion 为 null/空白时抛出 IllegalArgumentException（版本字符串本身不去除空白）；
     * calculatedAt 为 null 时抛出 NullPointerException。局限说明去除 null、去除首尾空白、丢弃空串并去重，结果为不可变列表。
     */
    public DerivationMetadata {
        if (algorithmVersion == null || algorithmVersion.isBlank()) throw new IllegalArgumentException("algorithmVersion is required");
        if (ruleVersion == null || ruleVersion.isBlank()) throw new IllegalArgumentException("ruleVersion is required");
        Objects.requireNonNull(calculatedAt, "calculatedAt must not be null");
        limitations = limitations == null ? List.of() : limitations.stream().filter(Objects::nonNull)
                .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }
}
