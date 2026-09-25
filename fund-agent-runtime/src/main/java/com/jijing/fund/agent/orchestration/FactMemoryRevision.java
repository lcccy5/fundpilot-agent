package com.jijing.fund.agent.orchestration;

import com.jijing.fund.agent.api.AgentFactCard;
import com.jijing.fund.agent.api.EvidenceReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Decides which fact-card folders die when source data is revised. */
public final class FactMemoryRevision {
    private FactMemoryRevision() {}

    public static List<String> categoriesFor(String eventType) {
        return switch (eventType == null ? "" : eventType) {
            case "FUND_NAV_UPDATED" -> List.of("NAV", "PROFILE", "METRICS");
            case "PORTFOLIO_TRANSACTION_RECORDED" -> List.of("HOLDINGS", "USER_CONTEXT");
            case "DOCUMENT_VERSION_ACTIVATED" -> List.of("DOCUMENTS");
            default -> List.of();
        };
    }

    public static String contentHash(String dataJson) {
        String value = dataJson == null ? "" : dataJson;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Fills revision, hash, and the card this observation replaces. */
    public static AgentFactCard stamp(AgentFactCard card, String supersededCardId) {
        String hash = card.contentHash() == null || card.contentHash().isBlank() ? contentHash(card.dataJson()) : card.contentHash();
        String revision = card.sourceRevision() == null || card.sourceRevision().isBlank() ? revisionOf(card, hash) : card.sourceRevision();
        String superseded = supersededCardId == null || supersededCardId.isBlank() ? card.supersedesCardId() : supersededCardId;
        return new AgentFactCard(card.cardId(), card.conversationId(), card.runId(), card.toolName(), card.subjectKey(),
                card.evidence(), card.dataJson(), card.createdAt(), card.expiresAt(), revision, hash, superseded);
    }

    private static String revisionOf(AgentFactCard card, String hash) {
        return card.evidence().stream()
                .map(evidence -> evidence.versionId() != null ? evidence.versionId() : evidence.dataVersion())
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(hash);
    }
}
