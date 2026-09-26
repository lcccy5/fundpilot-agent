package com.jijing.fund.agent.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 月报任务的存储端口，记录任务状态和已发布的内容版本。
 * 任务不存在或不属于调用方时，查询返回空，写入则拒绝。
 */
public interface ReportJobStore {

    /**
     * 创建一条执行中的月报任务并返回视图。
     * 不校验所有者或运行是否存在；标识由实现生成。
     */
    ReportJobView create(String ownerUserId, String runId, LocalDate periodStart, LocalDate periodEnd, Instant now);

    /**
     * 按标识读取调用方拥有的月报任务。
     * 不存在或所有者不匹配时返回空，不抛异常。
     */
    Optional<ReportJobView> findOwned(String jobId, String ownerUserId);

    /**
     * 列出某所有者的全部月报任务。
     * 没有任务时返回空列表；所有者为空时不会匹配到有所有者的任务。
     */
    List<ReportJobView> listOwned(String ownerUserId);

    /**
     * 为已有任务保存一个新版本的产物，并把任务标成成功。
     * 任务不存在或所有者不匹配时拒绝，不增加版本。
     */
    void saveVersionedArtifact(
            String jobId,
            String ownerUserId,
            String contentUri,
            String contentHash,
            String evidenceJson,
            Instant cutoff,
            String promptVersion,
            String modelVersion,
            Instant now);

    /**
     * 返回该任务已经保存的最新版本号，尚未保存时为 0。
     * 任务不存在或所有者不匹配时拒绝。
     */
    int latestVersion(String jobId, String ownerUserId);

    /**
     * 月报任务的只读视图，包含关联运行、所有者和区间。
     * 不校验区间开始是否早于结束。
     */
    record ReportJobView(
            String jobId,
            String runId,
            String ownerUserId,
            String status,
            LocalDate periodStart,
            LocalDate periodEnd) {
    }
}
