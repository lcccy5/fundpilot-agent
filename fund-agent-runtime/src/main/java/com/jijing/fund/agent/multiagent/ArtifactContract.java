package com.jijing.fund.agent.multiagent;

/** 实现 ArtifactContract 所代表的 Agent 运行时职责。 */
public final class ArtifactContract {
    
    /** 在继续处理前校验 validate 对应的输入或状态。 */
    public void validate(StructuredArtifact artifact){
        if(artifact==null||artifact.artifactType()==null||artifact.producerRole()==null)throw new IllegalArgumentException("artifact metadata is required");
        if(!"v1".equals(artifact.schemaVersion()))throw new IllegalArgumentException("unsupported artifact schema");
        if(artifact.claims()==null||artifact.claims().isEmpty())throw new IllegalArgumentException("claims are required");
        if(artifact.producerRole()==AgentRole.WRITER)throw new IllegalArgumentException("writer cannot produce research artifacts");
        for(var claim:artifact.claims()){
            if(claim.evidenceId()==null||claim.evidenceId().isBlank())throw new IllegalArgumentException("claim evidence is required");
            if(artifact.producerRole()==AgentRole.DATA_RESEARCHER&&"USER".equals(claim.ownerScope()))
                throw new IllegalArgumentException("data researcher cannot emit user-scoped claims");
        }
    }
}
