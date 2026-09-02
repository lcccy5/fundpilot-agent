package com.jijing.fund.infrastructure.report;

import com.jijing.fund.agent.report.ReportJobStore;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReportJobStore implements ReportJobStore {
    private final JdbcTemplate jdbc;
    public JdbcReportJobStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public ReportJobView create(String ownerUserId,String runId,LocalDate periodStart,LocalDate periodEnd,Instant now){
        String id=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO report_job(job_id,schedule_id,owner_user_id,run_id,status,period_start,period_end,created_at)
                VALUES(?,?,?,?,?,?,?,?)
                """,id,null,ownerUserId,runId,"RUNNING",Date.valueOf(periodStart),Date.valueOf(periodEnd),Timestamp.from(now));
        return new ReportJobView(id,runId,ownerUserId,"RUNNING",periodStart,periodEnd);
    }
    @Override public Optional<ReportJobView> findOwned(String jobId,String ownerUserId){
        var rows=jdbc.query("""
                SELECT job_id,run_id,owner_user_id,status,period_start,period_end FROM report_job WHERE job_id=? AND owner_user_id=?
                """,(rs,n)->new ReportJobView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getDate(5).toLocalDate(),rs.getDate(6).toLocalDate()),jobId,ownerUserId);
        return rows.stream().findFirst();
    }
    @Override public List<ReportJobView> listOwned(String ownerUserId){
        return jdbc.query("""
                SELECT job_id,run_id,owner_user_id,status,period_start,period_end FROM report_job WHERE owner_user_id=? ORDER BY created_at DESC
                """,(rs,n)->new ReportJobView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getDate(5).toLocalDate(),rs.getDate(6).toLocalDate()),ownerUserId);
    }
    @Override public void saveVersionedArtifact(String jobId,String ownerUserId,String contentUri,String contentHash,String evidenceJson,Instant cutoff,String promptVersion,String modelVersion,Instant now){
        if(findOwned(jobId,ownerUserId).isEmpty())throw new IllegalArgumentException("report job not found");
        String artifactId=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO report_artifact(artifact_id,job_id,owner_user_id,content_uri,content_hash,media_type,evidence_manifest_json,data_cutoff,prompt_version,model_version,created_at)
                VALUES(?,?,?,?,?,?,CAST(? AS JSON),?,?,?,?)
                """,artifactId,jobId,ownerUserId,contentUri,contentHash,"text/markdown",evidenceJson,Timestamp.from(cutoff),promptVersion,modelVersion,Timestamp.from(now));
        Integer next=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM report_version WHERE job_id=?",Integer.class,jobId);
        jdbc.update("INSERT INTO report_version(job_id,version_no,artifact_id,created_at) VALUES(?,?,?,?)",jobId,next==null?1:next,artifactId,Timestamp.from(now));
        jdbc.update("UPDATE report_job SET status='SUCCEEDED' WHERE job_id=? AND owner_user_id=?",jobId,ownerUserId);
    }
    @Override public int latestVersion(String jobId,String ownerUserId){
        var rows=jdbc.query("SELECT COALESCE(MAX(v.version_no),0) FROM report_version v JOIN report_job j ON j.job_id=v.job_id WHERE v.job_id=? AND j.owner_user_id=?",(rs,n)->rs.getInt(1),jobId,ownerUserId);
        return rows.isEmpty()?0:rows.getFirst();
    }
}
