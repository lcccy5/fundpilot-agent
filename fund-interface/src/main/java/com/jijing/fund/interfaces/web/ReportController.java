package com.jijing.fund.interfaces.web;

import com.jijing.fund.agent.report.MonthlyReportLauncher;
import com.jijing.fund.agent.report.ReportJobStore;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final ReportJobStore jobs;
    private final MonthlyReportLauncher launcher;
    public ReportController(ReportJobStore jobs,MonthlyReportLauncher launcher){this.jobs=jobs;this.launcher=launcher;}
    @GetMapping
    public ApiResponse<List<ReportJobStore.ReportJobView>> list(@CurrentUser AuthenticatedUser actor,HttpServletRequest request){
        var owner=actor.userId().value();
        jobs.listOwned(owner).forEach(job->launcher.reconcile(job,owner));
        return ApiResponse.success(RequestIdFilter.get(request),jobs.listOwned(owner));
    }
    @PostMapping("/monthly")
    public ApiResponse<ReportJobStore.ReportJobView> launchMonthly(@CurrentUser AuthenticatedUser actor,HttpServletRequest request){
        var run=launcher.launch(actor.userId().value(),0.80,0.81,1.1);
        var job=jobs.listOwned(actor.userId().value()).stream().filter(j->run.runId().equals(j.runId())).findFirst()
                .orElse(new ReportJobStore.ReportJobView(null,run.runId(),actor.userId().value(),run.status(),null,null));
        return ApiResponse.success(RequestIdFilter.get(request),job);
    }
}
