package com.jijing.fund.interfaces.web;

import com.jijing.fund.agent.report.MonthlyReportLauncher;
import com.jijing.fund.agent.report.ReportJobStore;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户的月报任务。匿名请求在安全过滤器返回 401，缺少登录主体时参数解析也返回 401。
 * 列表在返回前按属主对账；对账或启动过程中的模型不可用返回 503，未分类异常返回 500。
 * 本控制器没有独立的“任务不存在”分支，他人的任务不会出现在属主列表中。
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final ReportJobStore jobs;
    private final MonthlyReportLauncher launcher;

    /**
     * 绑定属主可见的任务存储，以及复用计划执行器的月报启动器。
     */
    public ReportController(ReportJobStore jobs, MonthlyReportLauncher launcher) {
        this.jobs = jobs;
        this.launcher = launcher;
    }

    /**
     * 列出当前用户的月报任务，并对尚未完成的任务做一次对账。
     */
    @GetMapping
    public ApiResponse<List<ReportJobStore.ReportJobView>> list(@CurrentUser AuthenticatedUser actor,
            HttpServletRequest request) {
        var owner = actor.userId().value();
        jobs.listOwned(owner).forEach(job -> launcher.reconcile(job, owner));
        return ApiResponse.success(RequestIdFilter.get(request), jobs.listOwned(owner));
    }

    /**
     * 为当前用户启动一份月报。模型关闭或不可用时返回 503。
     */
    @PostMapping("/monthly")
    public ApiResponse<ReportJobStore.ReportJobView> launchMonthly(@CurrentUser AuthenticatedUser actor,
            HttpServletRequest request) {
        var run = launcher.launch(actor.userId().value(), 0.80, 0.81, 1.1);
        var job = jobs.listOwned(actor.userId().value()).stream()
                .filter(j -> run.runId().equals(j.runId()))
                .findFirst()
                .orElse(new ReportJobStore.ReportJobView(null, run.runId(), actor.userId().value(), run.status(), null, null));
        return ApiResponse.success(RequestIdFilter.get(request), job);
    }
}
