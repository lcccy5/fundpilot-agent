package com.jijing.fund.infrastructure.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.domain.*;
import com.jijing.fund.knowledge.port.KnowledgeJobRepository;
import com.jijing.fund.knowledge.exception.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class JdbcKnowledgeJobRepository implements KnowledgeJobRepository {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    public JdbcKnowledgeJobRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    @Override @Transactional public Optional<KnowledgeJobWorkItem> claim(String workerId,Instant now,Duration lease){
        List<String>ids=jdbc.query("""
                SELECT job_id FROM knowledge_ingestion_job
                WHERE status IN ('REGISTERED','FAILED_RETRYABLE')
                  AND (next_retry_at IS NULL OR next_retry_at<=?)
                  AND (lease_until IS NULL OR lease_until<?)
                ORDER BY started_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """,(rs,n)->rs.getString(1),ts(now),ts(now));
        if(ids.isEmpty())return Optional.empty();String id=ids.getFirst();int changed=jdbc.update("""
                UPDATE knowledge_ingestion_job SET lease_owner=?,lease_until=?,attempt_count=attempt_count+1,
                status=COALESCE(last_completed_step,'REGISTERED'),current_step=COALESCE(last_completed_step,'REGISTERED'),error_code=NULL,
                safe_error_message=NULL,updated_at=? WHERE job_id=?
                """,workerId,ts(now.plus(lease)),ts(now),id);if(changed!=1)return Optional.empty();
        jdbc.update("UPDATE knowledge_document_version v JOIN knowledge_ingestion_job j ON j.version_id=v.version_id SET v.status=COALESCE(j.last_completed_step,'REGISTERED') WHERE j.job_id=? AND v.status='FAILED_RETRYABLE'",id);
        return Optional.of(loadWorkItem(id));
    }
    private KnowledgeJobWorkItem loadWorkItem(String id){Map<String,Object>r=jdbc.queryForMap("""
            SELECT j.job_id,j.attempt_count,j.last_completed_step,j.embedded_batch_no,v.version_id,v.storage_key,v.content_type,
                   d.document_id,d.title,d.document_type,d.published_date,d.source_name,d.source_uri
            FROM knowledge_ingestion_job j JOIN knowledge_document_version v ON v.version_id=j.version_id
            JOIN knowledge_document d ON d.document_id=v.document_id WHERE j.job_id=?
            """,id);String doc=(String)r.get("document_id");Set<String>funds=new LinkedHashSet<>(jdbc.query("SELECT fund_code FROM knowledge_document_fund WHERE document_id=? ORDER BY fund_code",(rs,n)->rs.getString(1),doc));String storage=(String)r.get("storage_key");return new KnowledgeJobWorkItem(id,doc,(String)r.get("version_id"),storage,storage,(String)r.get("content_type"),(String)r.get("title"),FundDocumentType.valueOf((String)r.get("document_type")),localDate(r.get("published_date")),funds,(String)r.get("source_name"),(String)r.get("source_uri"),((Number)r.get("attempt_count")).intValue(),(String)r.get("last_completed_step"),((Number)r.get("embedded_batch_no")).intValue());}
    @Override public boolean heartbeat(String jobId,String workerId,Instant now,Duration lease){return jdbc.update("UPDATE knowledge_ingestion_job SET lease_until=?,updated_at=? WHERE job_id=? AND lease_owner=? AND lease_until>=?",ts(now.plus(lease)),ts(now),jobId,workerId,ts(now))==1;}
    @Override public void checkpoint(String jobId,String step,int batch,int indexed,Instant now){jdbc.update("""
            UPDATE knowledge_ingestion_job SET current_step=?,last_completed_step=?,
            embedded_batch_no=GREATEST(embedded_batch_no,?),indexed_chunk_count=GREATEST(indexed_chunk_count,?),updated_at=? WHERE job_id=?
            """,step,step,batch,indexed,ts(now),jobId);}
    @Override public void releaseSuccess(String jobId,Instant now){jdbc.update("UPDATE knowledge_ingestion_job SET status='READY',current_step='READY',last_completed_step='READY',lease_owner=NULL,lease_until=NULL,next_retry_at=NULL,completed_at=?,updated_at=? WHERE job_id=?",ts(now),ts(now),jobId);}
    @Override public void releaseFailure(String jobId,IngestionStatus status,String code,String message,Instant next,Instant now){jdbc.update("UPDATE knowledge_ingestion_job SET status=?,lease_owner=NULL,lease_until=NULL,next_retry_at=?,error_code=?,safe_error_message=?,completed_at=?,updated_at=? WHERE job_id=?",status.name(),next==null?null:ts(next),code,message,status==IngestionStatus.FAILED_RETRYABLE?null:ts(now),ts(now),jobId);}
    @Override public KnowledgeJobView findJob(String id){List<KnowledgeJobView>rows=jdbc.query("""
            SELECT j.*,v.document_id FROM knowledge_ingestion_job j JOIN knowledge_document_version v ON v.version_id=j.version_id WHERE j.job_id=?
            """,(rs,n)->job(rs),id);return rows.stream().findFirst().orElseThrow(()->new KnowledgeNotFoundException("Knowledge job not found"));}
    @Override public KnowledgeVersionView findVersion(String id){List<KnowledgeVersionView>rows=jdbc.query("SELECT * FROM knowledge_document_version WHERE version_id=?",(rs,n)->version(rs),id);return rows.stream().findFirst().orElseThrow(()->new KnowledgeNotFoundException("Knowledge version not found"));}
    @Override public KnowledgeDocumentView findDocument(String id){List<Map<String,Object>>rows=jdbc.queryForList("SELECT * FROM knowledge_document WHERE document_id=?",id);if(rows.isEmpty())throw new KnowledgeNotFoundException("Knowledge document not found");Map<String,Object>r=rows.getFirst();List<KnowledgeVersionView>versions=jdbc.query("SELECT * FROM knowledge_document_version WHERE document_id=? ORDER BY version_no DESC",(rs,n)->version(rs),id);Set<String>funds=new LinkedHashSet<>(jdbc.query("SELECT fund_code FROM knowledge_document_fund WHERE document_id=? ORDER BY fund_code",(rs,n)->rs.getString(1),id));return new KnowledgeDocumentView(id,(String)r.get("external_document_id"),(String)r.get("title"),FundDocumentType.valueOf((String)r.get("document_type")),(String)r.get("publisher"),(String)r.get("source_name"),(String)r.get("source_uri"),localDate(r.get("published_date")),(String)r.get("active_version_id"),funds,versions,instant(r.get("created_at")),instant(r.get("updated_at")));}
    @Override @Transactional public KnowledgeJobView retry(String id,Instant now){KnowledgeJobView job=findJob(id);if(job.status()!=IngestionStatus.FAILED_RETRYABLE&&job.status()!=IngestionStatus.FAILED_FINAL)throw new KnowledgeConflictException("Only failed jobs can be retried");jdbc.update("UPDATE knowledge_document_version SET status='REGISTERED' WHERE version_id=?",job.versionId());jdbc.update("UPDATE knowledge_ingestion_job SET status='REGISTERED',current_step='REGISTERED',last_completed_step=NULL,next_retry_at=NULL,lease_owner=NULL,lease_until=NULL,error_code=NULL,safe_error_message=NULL,embedded_batch_no=0,indexed_chunk_count=0,completed_at=NULL,updated_at=? WHERE job_id=?",ts(now),id);return findJob(id);}
    private KnowledgeJobView job(ResultSet rs)throws SQLException{return new KnowledgeJobView(rs.getString("job_id"),rs.getString("document_id"),rs.getString("version_id"),IngestionStatus.valueOf(rs.getString("status")),rs.getString("current_step"),rs.getString("last_completed_step"),rs.getInt("attempt_count"),(Integer)rs.getObject("chunk_count"),rs.getInt("embedded_batch_no"),rs.getInt("indexed_chunk_count"),rs.getString("lease_owner"),instant(rs.getTimestamp("lease_until")),instant(rs.getTimestamp("next_retry_at")),rs.getString("error_code"),rs.getString("safe_error_message"),instant(rs.getTimestamp("started_at")),instant(rs.getTimestamp("updated_at")),instant(rs.getTimestamp("completed_at")));}
    private KnowledgeVersionView version(ResultSet rs)throws SQLException{return new KnowledgeVersionView(rs.getString("version_id"),rs.getString("document_id"),rs.getInt("version_no"),rs.getString("content_sha256"),rs.getString("content_type"),rs.getLong("file_size"),(Integer)rs.getObject("page_count"),(Long)rs.getObject("text_char_count"),IngestionStatus.valueOf(rs.getString("status")),rs.getString("parser_version"),rs.getString("chunking_version"),rs.getString("embedding_version"),rs.getString("index_name"),jsonList(rs.getString("warnings_json")),instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("ready_at")));}
    private List<String>jsonList(String value){try{return value==null?List.of():mapper.readValue(value,new TypeReference<>(){});}catch(Exception e){return List.of();}}
    private Timestamp ts(Instant v){return v==null?null:Timestamp.from(v);}private Instant instant(Object v){return v==null?null:((Timestamp)v).toInstant();}private LocalDate localDate(Object v){return v==null?null:((java.sql.Date)v).toLocalDate();}
}
