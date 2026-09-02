package com.jijing.fund.infrastructure.report;

import com.jijing.fund.agent.report.MonthlyReportLauncher;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReportScheduleWorker {
    private final JdbcTemplate jdbc;
    private final MonthlyReportLauncher launcher;
    public ReportScheduleWorker(JdbcTemplate jdbc,MonthlyReportLauncher launcher){this.jdbc=jdbc;this.launcher=launcher;}
    @Scheduled(fixedDelay=60000)
    public void tick(){
        Instant now=Instant.now();
        List<String[]> due=jdbc.query("""
                SELECT schedule_id,owner_user_id FROM report_schedule
                WHERE enabled=1 AND next_run_at<=? LIMIT 20
                """,(rs,n)->new String[]{rs.getString(1),rs.getString(2)},Timestamp.from(now));
        for(String[] row:due){
            launcher.launch(row[1],0.80,0.81,1.1);
            jdbc.update("UPDATE report_schedule SET next_run_at=? WHERE schedule_id=?",Timestamp.from(now.plus(30,ChronoUnit.DAYS)),row[0]);
        }
    }
}
