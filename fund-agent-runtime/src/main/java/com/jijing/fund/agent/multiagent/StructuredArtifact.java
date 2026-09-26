package com.jijing.fund.agent.multiagent;

import java.time.Instant;
import java.util.List;

/**
 * 对等代理交给下游的结构化产物。
 * 类型、模式版本、生产者角色和声明必须同时有效；契约校验失败时产物被拒绝，不能进入撰写或核验。
 * 计划为空、路由未启动多代理或审批被拒时，不会产生本对象。
 */
public record StructuredArtifact(
        String artifactType,
        String schemaVersion,
        AgentRole producerRole,
        String inputHash,
        Instant dataCutoff,
        List<Claim> claims,
        List<String> limitations,
        String contentHash) {

    /**
     * 产物中的一条可核验声明。
     * 缺少证据标识或由无权角色发出用户范围声明时，整份产物校验失败。
     */
    public record Claim(String claimId, String statement, String evidenceId, String ownerScope) {
    }
}
