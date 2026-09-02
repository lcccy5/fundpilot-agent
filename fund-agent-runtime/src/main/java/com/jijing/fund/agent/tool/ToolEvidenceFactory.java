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

/** Converts typed Domain lineage to the V1-compatible evidence shape at the Agent boundary. */
public final class ToolEvidenceFactory {
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    
    /** 创建并初始化当前 Agent 操作所需的 create 结果。 */
    public List<EvidenceReference> create(String evidenceScope, String fundCode, DataLineage lineage) {
        if (evidenceScope == null || evidenceScope.isBlank()) throw new IllegalArgumentException("evidenceScope is required");
        if (lineage == null) throw new IllegalArgumentException("lineage is required");
        List<EvidenceReference> evidence = new ArrayList<>();
        for (DataProvenance input : lineage.inputs()) evidence.add(source(evidenceScope, fundCode, input));
        if (lineage.derivation() != null) evidence.add(derived(evidenceScope, fundCode, lineage.derivation(), lineage.inputs()));
        return List.copyOf(evidence);
    }

    
    /** 执行该 Agent 运行时组件中的 source 操作。 */
    private EvidenceReference source(String scope, String fundCode, DataProvenance input) {
        LocalDate date = input.sourceUpdatedAt() == null ? null : input.sourceUpdatedAt().atZone(CHINA).toLocalDate();
        String id = stableId(scope, input.providerId().value(), input.dataKind().name(), input.sourceVersion(), input.sourceUri().toString());
        return new EvidenceReference(id, input.dataKind().name(), fundCode, date, date, input.dataKind().name(),
                input.providerId().value(), input.sourceVersion(), null, input.collectedAt(), null, null, null, null,
                null, null, null, null, null, input.sourceUri().toString());
    }

    
    /** 执行该 Agent 运行时组件中的 derived 操作。 */
    private EvidenceReference derived(String scope, String fundCode, DerivationMetadata metadata,
                                      List<DataProvenance> inputs) {
        String sourceVersion = metadata.algorithmVersion() + ":" + metadata.ruleVersion();
        String source = inputs.stream().map(input -> input.providerId().value()).distinct().sorted()
                .reduce((left, right) -> left + "+" + right).orElse("derived");
        String id = stableId(scope, "derived", sourceVersion, source);
        return new EvidenceReference(id, "DERIVED_ANALYTICS", fundCode, null, null, "DERIVED_ANALYTICS", source,
                sourceVersion, metadata.algorithmVersion(), metadata.calculatedAt());
    }

    
    /** 执行该 Agent 运行时组件中的 stableId 操作。 */
    private String stableId(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return "ev-" + HexFormat.of().formatHex(digest.digest()).substring(0, 24);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot create evidence id", error);
        }
    }
}
