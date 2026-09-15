package com.jijing.fund.agent.api;

import java.time.Instant;
import java.util.List;

/** Deterministic short-term memory produced from a successful tool result. */
public record AgentFactCard(String cardId,String conversationId,String runId,String toolName,String subjectKey,
        List<EvidenceReference> evidence,String dataJson,Instant createdAt,Instant expiresAt) {
    public AgentFactCard { evidence=evidence==null?List.of():List.copyOf(evidence); }

    /** Stable folder file used for replacement and query-aware retrieval. */
    public String memoryCategory() {
        String name=toolName==null?"":toolName.toLowerCase(java.util.Locale.ROOT);
        if(name.contains("profile"))return "PROFILE";
        if(name.contains("realtime")||name.contains("quote"))return "REALTIME";
        if(name.contains("metric")||name.contains("comparison"))return "METRICS";
        if(name.contains("nav"))return "NAV";
        if(name.contains("holding")||name.contains("position"))return "HOLDINGS";
        if(name.contains("sector")||name.contains("event")||name.contains("catalyst")||name.contains("impact")||name.contains("industry"))return "MARKET_SIGNALS";
        if(name.contains("document")||name.contains("report"))return "DOCUMENTS";
        if(name.contains("personal")||name.contains("portfolio"))return "USER_CONTEXT";
        return "OTHER";
    }
    
    /** 执行该 Agent 运行时组件中的 evidenceIds 操作。 */
    public List<String> evidenceIds(){return evidence.stream().map(EvidenceReference::evidenceId).toList();}
}
