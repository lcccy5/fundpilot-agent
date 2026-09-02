package com.jijing.fund.agent.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 定义 ReportJobStore 在 Agent 运行时中的能力契约。 */
public interface ReportJobStore {
    
    /** 创建并初始化当前 Agent 操作所需的 create 结果。 */
    ReportJobView create(String ownerUserId,String runId,LocalDate periodStart,LocalDate periodEnd,Instant now);
    
    /** 获取当前 Agent 操作所需的 findOwned 结果。 */
    Optional<ReportJobView> findOwned(String jobId,String ownerUserId);
    
    /** 获取当前 Agent 操作所需的 listOwned 结果。 */
    List<ReportJobView> listOwned(String ownerUserId);
    
    /** 通过 saveVersionedArtifact 操作更新持久化或内存中的运行状态。 */
    void saveVersionedArtifact(String jobId,String ownerUserId,String contentUri,String contentHash,String evidenceJson,Instant cutoff,String promptVersion,String modelVersion,Instant now);
    
    /** 执行该 Agent 运行时组件中的 latestVersion 操作。 */
    int latestVersion(String jobId,String ownerUserId);
    
    /** 在 Agent 运行时边界间传递 ReportJobView 数据的不可变值对象。 */
    record ReportJobView(String jobId,String runId,String ownerUserId,String status,LocalDate periodStart,LocalDate periodEnd){}
}
