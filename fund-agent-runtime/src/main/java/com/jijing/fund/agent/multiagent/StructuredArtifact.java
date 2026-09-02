package com.jijing.fund.agent.multiagent;

import java.time.Instant;
import java.util.List;

/** 在 Agent 运行时边界间传递 StructuredArtifact 数据的不可变值对象。 */
public record StructuredArtifact(String artifactType,String schemaVersion,AgentRole producerRole,String inputHash,
                                 Instant dataCutoff,List<Claim> claims,List<String> limitations,String contentHash) {
    
    /** 在 Agent 运行时边界间传递 Claim 数据的不可变值对象。 */
    public record Claim(String claimId,String statement,String evidenceId,String ownerScope){}
}
