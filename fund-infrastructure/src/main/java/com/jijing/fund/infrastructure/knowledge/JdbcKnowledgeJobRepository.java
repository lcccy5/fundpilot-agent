package com.jijing.fund.infrastructure.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.api.KnowledgeDocumentView;
import com.jijing.fund.knowledge.api.KnowledgeJobView;
import com.jijing.fund.knowledge.api.KnowledgeVersionView;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.IngestionStatus;
import com.jijing.fund.knowledge.domain.KnowledgeJobWorkItem;
import com.jijing.fund.knowledge.exception.KnowledgeConflictException;
import com.jijing.fund.knowledge.exception.KnowledgeNotFoundException;
import com.jijing.fund.knowledge.port.KnowledgeJobRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 摄取任务的领取与检查点。领取使用 {@code FOR UPDATE SKIP LOCKED}，租约更新失败则返回空，避免两个工人拿到同一任务。
 * 警告 JSON 读坏时变成空列表。没有 HTTP 超时；数据库连接失败由 Spring 抛出。
 * 只有失败状态可以人工重试，其他状态抛出冲突。
 */
public class JdbcKnowledgeJobRepository implements KnowledgeJobRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 不在构造时领取任务。 */
    public JdbcKnowledgeJobRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * 只领取已登记或可重试、且租约已过期的任务。更新行数不是 1 时当作被别人抢走。
     * 可重试版本的状态会回到上次完成的步骤。
     */
    @Override
    @Transactional
    public Optional<KnowledgeJobWorkItem> claim(String workerId, Instant now, Duration lease) {
        List<String> ids = jdbc.query("""
                SELECT job_id FROM knowledge_ingestion_job
                WHERE status IN ('REGISTERED','FAILED_RETRYABLE')
                  AND (next_retry_at IS NULL OR next_retry_at<=?)
                  AND (lease_until IS NULL OR lease_until<?)
                ORDER BY started_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, n) -> rs.getString(1), ts(now), ts(now));
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        String id = ids.getFirst();
        int changed = jdbc.update("""
                UPDATE knowledge_ingestion_job SET lease_owner=?,lease_until=?,attempt_count=attempt_count+1,
                status=COALESCE(last_completed_step,'REGISTERED'),current_step=COALESCE(last_completed_step,'REGISTERED'),error_code=NULL,
                safe_error_message=NULL,updated_at=? WHERE job_id=?
                """, workerId, ts(now.plus(lease)), ts(now), id);
        if (changed != 1) {
            return Optional.empty();
        }
        jdbc.update("""
                UPDATE knowledge_document_version v
                JOIN knowledge_ingestion_job j ON j.version_id=v.version_id
                SET v.status=COALESCE(j.last_completed_step,'REGISTERED')
                WHERE j.job_id=? AND v.status='FAILED_RETRYABLE'
                """, id);
        return Optional.of(loadWorkItem(id));
    }

    /** 把任务、版本和文档拼成工人所需的一条工作项。基金代码按代码排序。 */
    private KnowledgeJobWorkItem loadWorkItem(String id) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT j.job_id,j.attempt_count,j.last_completed_step,j.embedded_batch_no,v.version_id,v.storage_key,v.content_type,
                       d.document_id,d.title,d.document_type,d.published_date,d.source_name,d.source_uri
                FROM knowledge_ingestion_job j JOIN knowledge_document_version v ON v.version_id=j.version_id
                JOIN knowledge_document d ON d.document_id=v.document_id WHERE j.job_id=?
                """, id);
        String documentId = (String) row.get("document_id");
        Set<String> funds = new LinkedHashSet<>(jdbc.query(
                "SELECT fund_code FROM knowledge_document_fund WHERE document_id=? ORDER BY fund_code",
                (rs, n) -> rs.getString(1), documentId));
        String storage = (String) row.get("storage_key");
        return new KnowledgeJobWorkItem(id, documentId, (String) row.get("version_id"), storage, storage,
                (String) row.get("content_type"), (String) row.get("title"),
                FundDocumentType.valueOf((String) row.get("document_type")), localDate(row.get("published_date")),
                funds, (String) row.get("source_name"), (String) row.get("source_uri"),
                ((Number) row.get("attempt_count")).intValue(), (String) row.get("last_completed_step"),
                ((Number) row.get("embedded_batch_no")).intValue());
    }

    /** 只有租约仍属于该工人且未过期时才延长。过期心跳返回 false，调用方应停止写回。 */
    @Override
    public boolean heartbeat(String jobId, String workerId, Instant now, Duration lease) {
        return jdbc.update("""
                UPDATE knowledge_ingestion_job SET lease_until=?,updated_at=?
                WHERE job_id=? AND lease_owner=? AND lease_until>=?
                """, ts(now.plus(lease)), ts(now), jobId, workerId, ts(now)) == 1;
    }

    /** 步骤和批次只前进不后退，重复检查点不会把进度打小。 */
    @Override
    public void checkpoint(String jobId, String step, int batch, int indexed, Instant now) {
        jdbc.update("""
                UPDATE knowledge_ingestion_job SET current_step=?,last_completed_step=?,
                embedded_batch_no=GREATEST(embedded_batch_no,?),indexed_chunk_count=GREATEST(indexed_chunk_count,?),updated_at=?
                WHERE job_id=?
                """, step, step, batch, indexed, ts(now), jobId);
    }

    /** 成功后清掉租约和重试时间。 */
    @Override
    public void releaseSuccess(String jobId, Instant now) {
        jdbc.update("""
                UPDATE knowledge_ingestion_job
                SET status='READY',current_step='READY',last_completed_step='READY',lease_owner=NULL,lease_until=NULL,
                    next_retry_at=NULL,completed_at=?,updated_at=?
                WHERE job_id=?
                """, ts(now), ts(now), jobId);
    }

    /** 可重试失败不写完成时间；终态失败才写。下次重试时间由调用方决定。 */
    @Override
    public void releaseFailure(String jobId, IngestionStatus status, String code, String message, Instant next,
            Instant now) {
        jdbc.update("""
                UPDATE knowledge_ingestion_job
                SET status=?,lease_owner=NULL,lease_until=NULL,next_retry_at=?,error_code=?,safe_error_message=?,
                    completed_at=?,updated_at=?
                WHERE job_id=?
                """, status.name(), next == null ? null : ts(next), code, message,
                status == IngestionStatus.FAILED_RETRYABLE ? null : ts(now), ts(now), jobId);
    }

    /** 任务不存在时抛出知识库未找到，而不是空可选。 */
    @Override
    public KnowledgeJobView findJob(String id) {
        List<KnowledgeJobView> rows = jdbc.query("""
                SELECT j.*,v.document_id FROM knowledge_ingestion_job j
                JOIN knowledge_document_version v ON v.version_id=j.version_id WHERE j.job_id=?
                """, (rs, n) -> job(rs), id);
        return rows.stream().findFirst().orElseThrow(() -> new KnowledgeNotFoundException("Knowledge job not found"));
    }

    /** 版本不存在时同样抛出未找到。 */
    @Override
    public KnowledgeVersionView findVersion(String id) {
        List<KnowledgeVersionView> rows = jdbc.query(
                "SELECT * FROM knowledge_document_version WHERE version_id=?", (rs, n) -> version(rs), id);
        return rows.stream().findFirst()
                .orElseThrow(() -> new KnowledgeNotFoundException("Knowledge version not found"));
    }

    /** 文档视图带上全部版本和关联基金。版本按版本号倒序。 */
    @Override
    public KnowledgeDocumentView findDocument(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM knowledge_document WHERE document_id=?", id);
        if (rows.isEmpty()) {
            throw new KnowledgeNotFoundException("Knowledge document not found");
        }
        Map<String, Object> row = rows.getFirst();
        List<KnowledgeVersionView> versions = jdbc.query(
                "SELECT * FROM knowledge_document_version WHERE document_id=? ORDER BY version_no DESC",
                (rs, n) -> version(rs), id);
        Set<String> funds = new LinkedHashSet<>(jdbc.query(
                "SELECT fund_code FROM knowledge_document_fund WHERE document_id=? ORDER BY fund_code",
                (rs, n) -> rs.getString(1), id));
        return new KnowledgeDocumentView(id, (String) row.get("external_document_id"), (String) row.get("title"),
                FundDocumentType.valueOf((String) row.get("document_type")), (String) row.get("publisher"),
                (String) row.get("source_name"), (String) row.get("source_uri"), localDate(row.get("published_date")),
                (String) row.get("active_version_id"), funds, versions, instant(row.get("created_at")),
                instant(row.get("updated_at")));
    }

    /**
     * 只有可重试或最终失败能重置。重置会清掉批次、错误和租约，版本回到 REGISTERED。
     */
    @Override
    @Transactional
    public KnowledgeJobView retry(String id, Instant now) {
        KnowledgeJobView job = findJob(id);
        if (job.status() != IngestionStatus.FAILED_RETRYABLE && job.status() != IngestionStatus.FAILED_FINAL) {
            throw new KnowledgeConflictException("Only failed jobs can be retried");
        }
        jdbc.update("UPDATE knowledge_document_version SET status='REGISTERED' WHERE version_id=?", job.versionId());
        jdbc.update("""
                UPDATE knowledge_ingestion_job
                SET status='REGISTERED',current_step='REGISTERED',last_completed_step=NULL,next_retry_at=NULL,
                    lease_owner=NULL,lease_until=NULL,error_code=NULL,safe_error_message=NULL,embedded_batch_no=0,
                    indexed_chunk_count=0,completed_at=NULL,updated_at=?
                WHERE job_id=?
                """, ts(now), id);
        return findJob(id);
    }

    /** 一行任务视图。可空时间列保持 null。 */
    private KnowledgeJobView job(ResultSet rs) throws SQLException {
        return new KnowledgeJobView(rs.getString("job_id"), rs.getString("document_id"), rs.getString("version_id"),
                IngestionStatus.valueOf(rs.getString("status")), rs.getString("current_step"),
                rs.getString("last_completed_step"), rs.getInt("attempt_count"), (Integer) rs.getObject("chunk_count"),
                rs.getInt("embedded_batch_no"), rs.getInt("indexed_chunk_count"), rs.getString("lease_owner"),
                instant(rs.getTimestamp("lease_until")), instant(rs.getTimestamp("next_retry_at")),
                rs.getString("error_code"), rs.getString("safe_error_message"), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("updated_at")), instant(rs.getTimestamp("completed_at")));
    }

    /** 一行版本视图。警告 JSON 损坏时为空列表。 */
    private KnowledgeVersionView version(ResultSet rs) throws SQLException {
        return new KnowledgeVersionView(rs.getString("version_id"), rs.getString("document_id"),
                rs.getInt("version_no"), rs.getString("content_sha256"), rs.getString("content_type"),
                rs.getLong("file_size"), (Integer) rs.getObject("page_count"),
                (Long) rs.getObject("text_char_count"), IngestionStatus.valueOf(rs.getString("status")),
                rs.getString("parser_version"), rs.getString("chunking_version"), rs.getString("embedding_version"),
                rs.getString("index_name"), jsonList(rs.getString("warnings_json")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("ready_at")));
    }

    /** 空值和坏 JSON 都当成没有警告。 */
    private List<String> jsonList(String value) {
        try {
            return value == null ? List.of() : mapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** null 时刻写成 SQL NULL。 */
    private Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /** 查询结果里的时间戳列。 */
    private Instant instant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    /** 查询结果里的日期列。 */
    private LocalDate localDate(Object value) {
        return value == null ? null : ((java.sql.Date) value).toLocalDate();
    }
}
