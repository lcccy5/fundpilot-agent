package com.jijing.fund.interfaces.web;

import com.jijing.fund.agent.exception.AgentInvalidArgumentException;
import com.jijing.fund.agent.report.MonthlyReportLauncher;
import com.jijing.fund.agent.report.MonthlyReportScope;
import com.jijing.fund.agent.report.ReportJobStore;
import com.jijing.fund.application.portfolio.PortfolioUseCase;
import com.jijing.fund.application.watchlist.WatchlistUseCase;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.portfolio.PortfolioId;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final ReportJobStore jobs;
    private final MonthlyReportLauncher launcher;
    private final PortfolioUseCase portfolios;
    private final WatchlistUseCase watchlists;
    public ReportController(ReportJobStore jobs,MonthlyReportLauncher launcher,PortfolioUseCase portfolios,WatchlistUseCase watchlists){
        this.jobs=jobs;this.launcher=launcher;this.portfolios=portfolios;this.watchlists=watchlists;
    }
    @GetMapping
    public ApiResponse<List<ReportJobStore.ReportJobView>> list(@CurrentUser AuthenticatedUser actor,HttpServletRequest request){
        var owner=actor.userId().value();
        jobs.listOwned(owner).forEach(job->launcher.reconcile(job,owner));
        return ApiResponse.success(RequestIdFilter.get(request),jobs.listOwned(owner));
    }
    @PostMapping("/monthly")
    public ApiResponse<ReportJobStore.ReportJobView> launchMonthly(@CurrentUser AuthenticatedUser actor,@RequestBody MonthlyReportBody body,HttpServletRequest request){
        var run=launcher.launch(actor.userId().value(),scope(actor,body));
        var job=jobs.listOwned(actor.userId().value()).stream().filter(j->run.runId().equals(j.runId())).findFirst()
                .orElse(new ReportJobStore.ReportJobView(null,run.runId(),actor.userId().value(),run.status(),null,null));
        return ApiResponse.success(RequestIdFilter.get(request),job);
    }

    private MonthlyReportScope scope(AuthenticatedUser actor,MonthlyReportBody body){
        if(body==null||body.month()==null||body.month().isBlank()||body.scopeKind()==null||body.scopeId()==null||body.scopeId().isBlank())
            throw new AgentInvalidArgumentException("请选择月份，以及一个组合或自选");
        if("WATCHLIST".equals(body.scopeKind())){
            var group=watchlists.list(actor).stream().filter(item->item.groupId().equals(body.scopeId())).findFirst()
                    .orElseThrow(()->new AgentInvalidArgumentException("找不到这个自选分组"));
            var codes=group.items().stream().map(item->item.fundCode().value()).distinct().toList();
            if(codes.isEmpty())throw new AgentInvalidArgumentException("自选「"+group.displayName()+"」里还没有基金，无法生成月报");
            return new MonthlyReportScope(body.month(),"WATCHLIST",group.displayName(),codes);
        }
        if(!"PORTFOLIO".equals(body.scopeKind()))throw new AgentInvalidArgumentException("请选择组合或自选");
        var portfolio=portfolios.list(actor).stream().filter(item->item.portfolioId().value().equals(body.scopeId())).findFirst()
                .orElseThrow(()->new AgentInvalidArgumentException("找不到这个组合"));
        var codes=portfolios.positions(actor,portfolio.portfolioId()).stream().map(item->item.fundCode().value()).distinct().toList();
        if(codes.isEmpty())throw new AgentInvalidArgumentException("组合「"+portfolio.displayName()+"」还没有持仓，无法生成月报");
        YearMonth month=parseMonth(body.month());
        LocalDate end=month.atEndOfMonth();
        boolean held=portfolios.transactions(actor,new PortfolioId(portfolio.portfolioId().value())).stream().anyMatch(tx->!tx.confirmDate().isAfter(end));
        if(!held)throw new AgentInvalidArgumentException("组合「"+portfolio.displayName()+"」在 "+body.month()+" 还没有确认交易，无法生成这个月的月报");
        return new MonthlyReportScope(body.month(),"PORTFOLIO",portfolio.displayName(),codes);
    }

    private static YearMonth parseMonth(String month){
        try{return YearMonth.parse(month);}
        catch(Exception error){throw new AgentInvalidArgumentException("月份格式应为 YYYY-MM");}
    }

    public record MonthlyReportBody(String month,String scopeKind,String scopeId){}
}
