package com.jijing.fund.infrastructure.report;

import com.jijing.fund.agent.report.MonthlyReportLauncher;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每分钟扫描到期的报告调度，最多 20 条。启动参数里的阈值是写死的 0.80、0.81 和 1.1。
 * 启动后把下次运行时间推后 30 天，即使启动抛错也不会更新，下一次 tick 会再次尝试。
 * 没有空载荷分支。数据库连接失败由 Spring 抛出并打断本轮。
 */
@Component
public class ReportScheduleWorker {
    private final JdbcTemplate jdbc;
    private final MonthlyReportLauncher launcher;

    /** 启动器负责真正创建报告运行。 */
    public ReportScheduleWorker(JdbcTemplate jdbc, MonthlyReportLauncher launcher) {
        this.jdbc = jdbc;
        this.launcher = launcher;
    }

    /** 固定延迟 60 秒。时间用系统时钟，不跟数据库时钟对齐。 */
    @Scheduled(fixedDelay = 60000)
    public void tick() {
        Instant now = Instant.now();
        List<String[]> due = jdbc.query("""
                SELECT schedule_id,owner_user_id FROM report_schedule
                WHERE enabled=1 AND next_run_at<=? LIMIT 20
                """, (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, Timestamp.from(now));
        for (String[] row : due) {
            launcher.launch(row[1], 0.80, 0.81, 1.1);
            jdbc.update("UPDATE report_schedule SET next_run_at=? WHERE schedule_id=?",
                    Timestamp.from(now.plus(30, ChronoUnit.DAYS)), row[0]);
        }
    }
}
