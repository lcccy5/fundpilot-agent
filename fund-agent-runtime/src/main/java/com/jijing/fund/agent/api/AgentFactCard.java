package com.jijing.fund.agent.api;

import java.time.Instant;
import java.util.List;

/** Deterministic short-term memory produced from a successful tool result. */
public record AgentFactCard(String cardId,String conversationId,String runId,String toolName,String subjectKey,
        List<EvidenceReference> evidence,String dataJson,Instant createdAt,Instant expiresAt) {
    public AgentFactCard { evidence=evidence==null?List.of():List.copyOf(evidence); }
    
    /** 执行该 Agent 运行时组件中的 evidenceIds 操作。 */
    public List<String> evidenceIds(){return evidence.stream().map(EvidenceReference::evidenceId).toList();}
}
