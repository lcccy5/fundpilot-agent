package com.jijing.fund.agent.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 实现 InMemoryReportJobStore 所代表的 Agent 运行时职责。 */
public final class InMemoryReportJobStore implements ReportJobStore {
    private final Map<String,ReportJobView> jobs=new ConcurrentHashMap<>();
    private final Map<String,Integer> versions=new ConcurrentHashMap<>();
    @Override 
    /** 创建并初始化当前 Agent 操作所需的 create 结果。 */
    public ReportJobView create(String ownerUserId,String runId,LocalDate periodStart,LocalDate periodEnd,Instant now){
        var view=new ReportJobView(UUID.randomUUID().toString(),runId,ownerUserId,"RUNNING",periodStart,periodEnd);
        jobs.put(view.jobId(),view);return view;
    }
    @Override 
    /** 获取当前 Agent 操作所需的 findOwned 结果。 */
    public Optional<ReportJobView> findOwned(String jobId,String ownerUserId){
        return Optional.ofNullable(jobs.get(jobId)).filter(j->j.ownerUserId().equals(ownerUserId));
    }
    @Override 
    /** 获取当前 Agent 操作所需的 listOwned 结果。 */
    public List<ReportJobView> listOwned(String ownerUserId){
        return jobs.values().stream().filter(j->j.ownerUserId().equals(ownerUserId)).toList();
    }
    @Override 
    /** 通过 saveVersionedArtifact 操作更新持久化或内存中的运行状态。 */
    public void saveVersionedArtifact(String jobId,String ownerUserId,String contentUri,String contentHash,String evidenceJson,Instant cutoff,String promptVersion,String modelVersion,Instant now){
        var job=findOwned(jobId,ownerUserId).orElseThrow();
        int next=versions.merge(jobId,1,Integer::sum);
        jobs.put(jobId,new ReportJobView(job.jobId(),job.runId(),job.ownerUserId(),"SUCCEEDED",job.periodStart(),job.periodEnd()));
        if(next<1)throw new IllegalStateException("version");
    }
    @Override 
    /** 执行该 Agent 运行时组件中的 latestVersion 操作。 */
    public int latestVersion(String jobId,String ownerUserId){
        findOwned(jobId,ownerUserId).orElseThrow();
        return versions.getOrDefault(jobId,0);
    }
}
