package com.jijing.fund.infrastructure.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.api.DocumentRegistrationResult;
import com.jijing.fund.knowledge.api.RegisterDocumentCommand;
import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.IngestionStatus;
import com.jijing.fund.knowledge.port.DocumentMetadataRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档、版本和摄取任务的 JDBC 存储。
 * 相同来源加上相同内容哈希，并且外部编号或来源 URI 也相同，视为重复提交，返回已有记录且 {@code duplicate=true}，不再插入。
 * 警告列表序列化失败时写成空数组 {@code []}，不让元数据写入失败。
 * 没有 HTTP 超时或连接失败分支；数据库异常直接抛出。
 */
public class JdbcDocumentMetadataRepository implements DocumentMetadataRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 保存模板和 JSON 序列化器，不在构造时访问数据库。 */
    public JdbcDocumentMetadataRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * 先查重复，再决定复用文档或新建。新版本从 REGISTERED 开始，并带一条任务。
     */
    @Override
    @Transactional
    public DocumentRegistrationResult register(RegisterDocumentCommand command, String hash, String storageKey,
            Instant now) {
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT d.document_id,v.version_id,j.job_id,v.status FROM knowledge_document d
                JOIN knowledge_document_version v ON v.document_id=d.document_id
                LEFT JOIN knowledge_ingestion_job j ON j.version_id=v.version_id
                WHERE d.source_name=? AND v.content_sha256=? AND ((d.external_document_id IS NOT NULL AND d.external_document_id=?) OR (d.external_document_id IS NULL AND d.source_uri=?)) LIMIT 1
                """, command.sourceName(), hash, command.externalDocumentId(),
                command.sourceUri() == null ? null : command.sourceUri().toString());
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.getFirst();
            return new DocumentRegistrationResult((String) row.get("document_id"), (String) row.get("version_id"),
                    (String) row.get("job_id"), IngestionStatus.valueOf((String) row.get("status")), true);
        }
        String documentId = findDocument(command).orElseGet(() -> createDocument(command, now));
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM knowledge_document_version WHERE document_id=?",
                Integer.class, documentId);
        String versionId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO knowledge_document_version(version_id,document_id,version_no,content_sha256,storage_key,content_type,file_size,status,warnings_json,created_at)
                VALUES(?,?,?,?,?,?,?,?,CAST(? AS JSON),?)
                """, versionId, documentId, next, hash, storageKey, command.contentType(), command.content().length,
                IngestionStatus.REGISTERED.name(), "[]", ts(now));
        for (String code : command.fundCodes()) {
            jdbc.update("INSERT IGNORE INTO knowledge_document_fund(document_id,fund_code,relation_type) VALUES(?,?,?)",
                    documentId, code, "PRIMARY");
        }
        jdbc.update("""
                INSERT INTO knowledge_ingestion_job(job_id,version_id,status,current_step,attempt_count,started_at,updated_at)
                VALUES(?,?,?,?,?,?,?)
                """, jobId, versionId, IngestionStatus.REGISTERED.name(), IngestionStatus.REGISTERED.name(), 0,
                ts(now), ts(now));
        return new DocumentRegistrationResult(documentId, versionId, jobId, IngestionStatus.REGISTERED, false);
    }

    /** 有外部编号时按来源加编号找文档，否则按来源加 URI。都没有命中则调用方新建。 */
    private Optional<String> findDocument(RegisterDocumentCommand command) {
        List<String> ids;
        if (command.externalDocumentId() != null && !command.externalDocumentId().isBlank()) {
            ids = jdbc.query(
                    "SELECT document_id FROM knowledge_document WHERE source_name=? AND external_document_id=? LIMIT 1",
                    (rs, n) -> rs.getString(1), command.sourceName(), command.externalDocumentId());
        } else {
            ids = jdbc.query(
                    "SELECT document_id FROM knowledge_document WHERE source_name=? AND source_uri=? LIMIT 1",
                    (rs, n) -> rs.getString(1), command.sourceName(),
                    command.sourceUri() == null ? null : command.sourceUri().toString());
        }
        return ids.stream().findFirst();
    }

    /** 插入文档主记录。来源 URI 为空时列值为 null。 */
    private String createDocument(RegisterDocumentCommand command, Instant now) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO knowledge_document(document_id,external_document_id,title,document_type,publisher,source_name,source_uri,published_date,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, id, command.externalDocumentId(), command.title(), command.documentType().name(),
                command.publisher(), command.sourceName(),
                command.sourceUri() == null ? null : command.sourceUri().toString(), command.publishedDate(),
                ts(now), ts(now));
        return id;
    }

    /**
     * 只在期望状态匹配时前进。影响行数不是 1 说明并发或非法跃迁。
     * 进入 READY 时把旧的就绪版本标成 SUPERSEDED，并切换文档的活动版本。
     */
    @Override
    @Transactional
    public void transition(String versionId, IngestionStatus expected, IngestionStatus next, Instant at) {
        int changed = jdbc.update(
                "UPDATE knowledge_document_version SET status=? WHERE version_id=? AND status=?",
                next.name(), versionId, expected.name());
        if (changed != 1) {
            throw new IllegalStateException("Concurrent or illegal ingestion transition for " + versionId);
        }
        jdbc.update(
                "UPDATE knowledge_ingestion_job SET status=?,current_step=?,updated_at=? WHERE version_id=?",
                next.name(), next.name(), ts(at), versionId);
        if (next == IngestionStatus.READY) {
            List<String> old = jdbc.query("""
                    SELECT d.active_version_id FROM knowledge_document d
                    JOIN knowledge_document_version v ON v.document_id=d.document_id
                    WHERE v.version_id=? AND d.active_version_id IS NOT NULL
                    """, (rs, n) -> rs.getString(1), versionId);
            old.stream().filter(id -> !id.equals(versionId)).forEach(id -> jdbc.update(
                    "UPDATE knowledge_document_version SET status='SUPERSEDED' WHERE version_id=? AND status='READY'",
                    id));
            jdbc.update("""
                    UPDATE knowledge_document d
                    JOIN knowledge_document_version v ON v.document_id=d.document_id
                    SET d.active_version_id=v.version_id,d.updated_at=? WHERE v.version_id=?
                    """, ts(at), versionId);
            jdbc.update("UPDATE knowledge_document_version SET ready_at=? WHERE version_id=?", ts(at), versionId);
            jdbc.update("UPDATE knowledge_ingestion_job SET completed_at=? WHERE version_id=?", ts(at), versionId);
        }
    }

    /** 写回页数、字符数、解析器版本和警告。警告序列化失败时存空数组。 */
    @Override
    public void updateParsed(String versionId, int pages, long chars, String parserVersion, List<String> warnings,
            Instant at) {
        jdbc.update("""
                UPDATE knowledge_document_version
                SET page_count=?,text_char_count=?,parser_version=?,warnings_json=CAST(? AS JSON)
                WHERE version_id=?
                """, pages, chars, parserVersion, json(warnings), versionId);
    }

    /** 记录切块、嵌入和索引名，并把任务上的分块数更新为本次数量。 */
    @Override
    public void updateIndexed(String versionId, int chunks, String chunkVersion, String embeddingVersion,
            String indexName, Instant at) {
        jdbc.update("""
                UPDATE knowledge_document_version
                SET chunking_version=?,embedding_version=?,index_name=? WHERE version_id=?
                """, chunkVersion, embeddingVersion, indexName, versionId);
        jdbc.update("UPDATE knowledge_ingestion_job SET chunk_count=?,updated_at=? WHERE version_id=?",
                chunks, ts(at), versionId);
    }

    /** 版本和任务一起标失败，尝试次数加一。不在这里决定能否重试。 */
    @Override
    public void fail(String versionId, String jobId, IngestionStatus status, String code, String message, Instant at) {
        jdbc.update("UPDATE knowledge_document_version SET status=? WHERE version_id=?", status.name(), versionId);
        jdbc.update("""
                UPDATE knowledge_ingestion_job
                SET status=?,error_code=?,safe_error_message=?,attempt_count=attempt_count+1,updated_at=?
                WHERE job_id=?
                """, status.name(), code, message, ts(at), jobId);
    }

    /** 先删后插该版本的分块元数据。嵌入和索引状态重置为 PENDING。 */
    @Override
    @Transactional
    public void replaceChunkMetadata(String versionId, List<DocumentChunk> chunks, String artifact, Instant at) {
        jdbc.update("DELETE FROM knowledge_document_chunk_metadata WHERE version_id=?", versionId);
        for (DocumentChunk chunk : chunks) {
            jdbc.update("""
                    INSERT INTO knowledge_document_chunk_metadata(chunk_id,version_id,chunk_order,page_start,page_end,heading_path,token_count,content_sha256,artifact_storage_key,embedding_status,indexed_status,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,'PENDING','PENDING',?)
                    """, chunk.chunkId(), versionId, chunk.chunkOrder(), chunk.pageStart(), chunk.pageEnd(),
                    chunk.headingPath(), chunk.tokenCount(), chunk.contentSha256(), artifact, ts(at));
        }
    }

    /** 序列化失败时退回空数组，避免警告字段阻断主流程。 */
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }

    /** JDBC 时间戳。 */
    private Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }
}
