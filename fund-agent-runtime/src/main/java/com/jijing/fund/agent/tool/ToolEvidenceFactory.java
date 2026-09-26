package com.jijing.fund.agent.tool;

import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.domain.research.model.DataLineage;
import com.jijing.fund.domain.research.model.DataProvenance;
import com.jijing.fund.domain.research.model.DerivationMetadata;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 在 Agent 边界把领域数据谱系转换成兼容 V1 的证据。相同输入得到稳定证据编号，便于核对引用。
 * 范围或谱系缺失时直接拒绝；摘要算法不可用时抛出 IllegalStateException，不返回无编号证据。
 */
public final class ToolEvidenceFactory {
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    /**
     * 为每个来源输入生成一条证据；存在派生元数据时再追加一条派生证据。
     * evidenceScope 为空白或 lineage 为 null 时抛出 IllegalArgumentException，不生成部分列表。
     */
    public List<EvidenceReference> create(String evidenceScope, String fundCode, DataLineage lineage) {
        if (evidenceScope == null || evidenceScope.isBlank()) {
            throw new IllegalArgumentException("evidenceScope is required");
        }
        if (lineage == null) {
            throw new IllegalArgumentException("lineage is required");
        }
        List<EvidenceReference> evidence = new ArrayList<>();
        for (DataProvenance input : lineage.inputs()) {
            evidence.add(source(evidenceScope, fundCode, input));
        }
        if (lineage.derivation() != null) {
            evidence.add(derived(evidenceScope, fundCode, lineage.derivation(), lineage.inputs()));
        }
        return List.copyOf(evidence);
    }

    /**
     * 把单条来源谱系收成证据。来源更新时间为空时，证据上的起止日期也为空，不改用采集时间冒充行情日。
     */
    private EvidenceReference source(String scope, String fundCode, DataProvenance input) {
        LocalDate date = input.sourceUpdatedAt() == null ? null : input.sourceUpdatedAt().atZone(CHINA).toLocalDate();
        String id = stableId(scope, input.providerId().value(), input.dataKind().name(), input.sourceVersion(), input.sourceUri().toString());
        return new EvidenceReference(id, input.dataKind().name(), fundCode, date, date, input.dataKind().name(),
                input.providerId().value(), input.sourceVersion(), null, input.collectedAt(), null, null, null, null,
                null, null, null, null, null, input.sourceUri().toString());
    }

    /**
     * 把算法和规则版本收成派生证据。没有来源提供者时数据源记为 derived，不因此失败。
     */
    private EvidenceReference derived(String scope, String fundCode, DerivationMetadata metadata,
                                      List<DataProvenance> inputs) {
        String sourceVersion = metadata.algorithmVersion() + ":" + metadata.ruleVersion();
        String source = inputs.stream().map(input -> input.providerId().value()).distinct().sorted()
                .reduce((left, right) -> left + "+" + right).orElse("derived");
        String id = stableId(scope, "derived", sourceVersion, source);
        return new EvidenceReference(id, "DERIVED_ANALYTICS", fundCode, null, null, "DERIVED_ANALYTICS", source,
                sourceVersion, metadata.algorithmVersion(), metadata.calculatedAt());
    }

    /**
     * 用 SHA-256 前 24 位十六进制生成稳定证据编号。空片段按空字符串参与摘要。
     * 运行环境没有 SHA-256 时抛出 IllegalStateException。
     */
    private String stableId(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            }
            return "ev-" + HexFormat.of().formatHex(digest.digest()).substring(0, 24);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot create evidence id", error);
        }
    }
}
