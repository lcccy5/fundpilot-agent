package com.jijing.fund.agent.multiagent;

/**
 * 约束对等代理交换的结构化产物。
 * 元数据缺失、模式版本不是 v1、没有声明、撰写者产出研究产物、声明缺少证据，
 * 或数据研究员发出用户范围声明时，抛出 {@link IllegalArgumentException}，产物不得继续传递。
 * 计划校验失败和路由失败不在这里补救；审批拒绝也不能靠放宽契约绕过。
 */
public final class ArtifactContract {

    /**
     * 校验一份产物是否允许离开生产者。
     * 任一声明不满足证据或范围约束时整份产物失败，不会剔除坏声明后继续。
     * 对等代理因此失败时，监督者不能把未校验文本当作替代产物。
     */
    public void validate(StructuredArtifact artifact) {
        if (artifact == null || artifact.artifactType() == null || artifact.producerRole() == null) {
            throw new IllegalArgumentException("artifact metadata is required");
        }
        if (!"v1".equals(artifact.schemaVersion())) {
            throw new IllegalArgumentException("unsupported artifact schema");
        }
        if (artifact.claims() == null || artifact.claims().isEmpty()) {
            throw new IllegalArgumentException("claims are required");
        }
        if (artifact.producerRole() == AgentRole.WRITER) {
            throw new IllegalArgumentException("writer cannot produce research artifacts");
        }
        for (var claim : artifact.claims()) {
            if (claim.evidenceId() == null || claim.evidenceId().isBlank()) {
                throw new IllegalArgumentException("claim evidence is required");
            }
            if (artifact.producerRole() == AgentRole.DATA_RESEARCHER && "USER".equals(claim.ownerScope())) {
                throw new IllegalArgumentException("data researcher cannot emit user-scoped claims");
            }
        }
    }
}
