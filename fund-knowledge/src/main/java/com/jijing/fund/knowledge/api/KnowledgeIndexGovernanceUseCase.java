package com.jijing.fund.knowledge.api;
public interface KnowledgeIndexGovernanceUseCase {
    IndexRebuildView create();
    IndexRebuildView get(String rebuildId);
    IndexRebuildView activate(String rebuildId);
    IndexRebuildView rollback(String rebuildId);
}
