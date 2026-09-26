package com.jijing.fund.agent.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内的月报任务存储，只保留最新状态和版本计数。
 * 产物地址、摘要和证据不会被保存；任务缺失时写入和读取版本都会失败。
 */
public final class InMemoryReportJobStore implements ReportJobStore {
    private final Map<String, ReportJobView> jobs = new ConcurrentHashMap<>();
    private final Map<String, Integer> versions = new ConcurrentHashMap<>();

    /**
     * 创建一条执行中的月报任务。
     * 不校验运行是否存在；所有者为空时仍会保存，之后只能用空所有者读回。
     */
    @Override
    public ReportJobView create(
            String ownerUserId,
            String runId,
            LocalDate periodStart,
            LocalDate periodEnd,
            Instant now) {
        var view = new ReportJobView(UUID.randomUUID().toString(), runId, ownerUserId, "RUNNING", periodStart, periodEnd);
        jobs.put(view.jobId(), view);
        return view;
    }

    /**
     * 读取调用方拥有的任务。
     * 标识不存在或所有者不同时返回空。
     */
    @Override
    public Optional<ReportJobView> findOwned(String jobId, String ownerUserId) {
        return Optional.ofNullable(jobs.get(jobId)).filter(j -> j.ownerUserId().equals(ownerUserId));
    }

    /**
     * 列出该所有者的任务，顺序不稳定。
     * 没有匹配任务时返回空列表。
     */
    @Override
    public List<ReportJobView> listOwned(String ownerUserId) {
        return jobs.values().stream().filter(j -> j.ownerUserId().equals(ownerUserId)).toList();
    }

    /**
     * 把任务标成成功并把版本加一。
     * 任务不存在或所有者不匹配时抛出没有元素的异常；内容地址和摘要参数目前被忽略。
     */
    @Override
    public void saveVersionedArtifact(
            String jobId,
            String ownerUserId,
            String contentUri,
            String contentHash,
            String evidenceJson,
            Instant cutoff,
            String promptVersion,
            String modelVersion,
            Instant now) {
        var job = findOwned(jobId, ownerUserId).orElseThrow();
        int next = versions.merge(jobId, 1, Integer::sum);
        jobs.put(jobId, new ReportJobView(
                job.jobId(),
                job.runId(),
                job.ownerUserId(),
                "SUCCEEDED",
                job.periodStart(),
                job.periodEnd()));
        if (next < 1) {
            throw new IllegalStateException("version");
        }
    }

    /**
     * 返回已保存的版本号，没有保存过时为 0。
     * 任务不存在或所有者不匹配时抛出没有元素的异常。
     */
    @Override
    public int latestVersion(String jobId, String ownerUserId) {
        findOwned(jobId, ownerUserId).orElseThrow();
        return versions.getOrDefault(jobId, 0);
    }
}
