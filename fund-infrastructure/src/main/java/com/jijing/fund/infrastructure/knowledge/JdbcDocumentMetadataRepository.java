package com.jijing.fund.infrastructure.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.domain.IngestionStatus;
import com.jijing.fund.knowledge.port.DocumentMetadataRepository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class JdbcDocumentMetadataRepository implements DocumentMetadataRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    public JdbcDocumentMetadataRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    @Override @Transactional public DocumentRegistrationResult register(RegisterDocumentCommand c,String hash,String storageKey,Instant now){
        List<Map<String,Object>> existing=jdbc.queryForList("""
                SELECT d.document_id,v.version_id,j.job_id,v.status FROM knowledge_document d
                JOIN knowledge_document_version v ON v.document_id=d.document_id
                LEFT JOIN knowledge_ingestion_job j ON j.version_id=v.version_id
                WHERE d.source_name=? AND v.content_sha256=? AND ((d.external_document_id IS NOT NULL AND d.external_document_id=?) OR (d.external_document_id IS NULL AND d.source_uri=?)) LIMIT 1
                """,c.sourceName(),hash,c.externalDocumentId(),c.sourceUri()==null?null:c.sourceUri().toString());
        if(!existing.isEmpty()){var row=existing.getFirst();return new DocumentRegistrationResult((String)row.get("document_id"),(String)row.get("version_id"),(String)row.get("job_id"),IngestionStatus.valueOf((String)row.get("status")),true);}
        String documentId=findDocument(c).orElseGet(()->createDocument(c,now));Integer next=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM knowledge_document_version WHERE document_id=?",Integer.class,documentId);String versionId=UUID.randomUUID().toString(),jobId=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO knowledge_document_version(version_id,document_id,version_no,content_sha256,storage_key,content_type,file_size,status,warnings_json,created_at)
                VALUES(?,?,?,?,?,?,?,?,CAST(? AS JSON),?)
                """,versionId,documentId,next,hash,storageKey,c.contentType(),c.content().length,IngestionStatus.REGISTERED.name(),"[]",ts(now));
        for(String code:c.fundCodes())jdbc.update("INSERT IGNORE INTO knowledge_document_fund(document_id,fund_code,relation_type) VALUES(?,?,?)",documentId,code,"PRIMARY");
        jdbc.update("INSERT INTO knowledge_ingestion_job(job_id,version_id,status,current_step,attempt_count,started_at,updated_at) VALUES(?,?,?,?,?,?,?)",jobId,versionId,IngestionStatus.REGISTERED.name(),IngestionStatus.REGISTERED.name(),0,ts(now),ts(now));
        return new DocumentRegistrationResult(documentId,versionId,jobId,IngestionStatus.REGISTERED,false);
    }
    private Optional<String> findDocument(RegisterDocumentCommand c){List<String>ids;if(c.externalDocumentId()!=null&&!c.externalDocumentId().isBlank())ids=jdbc.query("SELECT document_id FROM knowledge_document WHERE source_name=? AND external_document_id=? LIMIT 1",(rs,n)->rs.getString(1),c.sourceName(),c.externalDocumentId());else ids=jdbc.query("SELECT document_id FROM knowledge_document WHERE source_name=? AND source_uri=? LIMIT 1",(rs,n)->rs.getString(1),c.sourceName(),c.sourceUri()==null?null:c.sourceUri().toString());return ids.stream().findFirst();}
    private String createDocument(RegisterDocumentCommand c,Instant now){String id=UUID.randomUUID().toString();jdbc.update("""
            INSERT INTO knowledge_document(document_id,external_document_id,title,document_type,publisher,source_name,source_uri,published_date,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,id,c.externalDocumentId(),c.title(),c.documentType().name(),c.publisher(),c.sourceName(),c.sourceUri()==null?null:c.sourceUri().toString(),c.publishedDate(),ts(now),ts(now));return id;}
    @Override @Transactional public void transition(String versionId,IngestionStatus expected,IngestionStatus next,Instant at){int changed=jdbc.update("UPDATE knowledge_document_version SET status=? WHERE version_id=? AND status=?",next.name(),versionId,expected.name());if(changed!=1)throw new IllegalStateException("Concurrent or illegal ingestion transition for "+versionId);jdbc.update("UPDATE knowledge_ingestion_job SET status=?,current_step=?,updated_at=? WHERE version_id=?",next.name(),next.name(),ts(at),versionId);if(next==IngestionStatus.READY){List<String>old=jdbc.query("SELECT d.active_version_id FROM knowledge_document d JOIN knowledge_document_version v ON v.document_id=d.document_id WHERE v.version_id=? AND d.active_version_id IS NOT NULL",(rs,n)->rs.getString(1),versionId);old.stream().filter(id->!id.equals(versionId)).forEach(id->jdbc.update("UPDATE knowledge_document_version SET status='SUPERSEDED' WHERE version_id=? AND status='READY'",id));jdbc.update("UPDATE knowledge_document d JOIN knowledge_document_version v ON v.document_id=d.document_id SET d.active_version_id=v.version_id,d.updated_at=? WHERE v.version_id=?",ts(at),versionId);jdbc.update("UPDATE knowledge_document_version SET ready_at=? WHERE version_id=?",ts(at),versionId);jdbc.update("UPDATE knowledge_ingestion_job SET completed_at=? WHERE version_id=?",ts(at),versionId);}}
    @Override public void updateParsed(String versionId,int pages,long chars,String parserVersion,List<String>warnings,Instant at){jdbc.update("UPDATE knowledge_document_version SET page_count=?,text_char_count=?,parser_version=?,warnings_json=CAST(? AS JSON) WHERE version_id=?",pages,chars,parserVersion,json(warnings),versionId);}
    @Override public void updateIndexed(String versionId,int chunks,String chunkVersion,String embeddingVersion,String indexName,Instant at){jdbc.update("UPDATE knowledge_document_version SET chunking_version=?,embedding_version=?,index_name=? WHERE version_id=?",chunkVersion,embeddingVersion,indexName,versionId);jdbc.update("UPDATE knowledge_ingestion_job SET chunk_count=?,updated_at=? WHERE version_id=?",chunks,ts(at),versionId);}
    @Override public void fail(String versionId,String jobId,IngestionStatus status,String code,String message,Instant at){jdbc.update("UPDATE knowledge_document_version SET status=? WHERE version_id=?",status.name(),versionId);jdbc.update("UPDATE knowledge_ingestion_job SET status=?,error_code=?,safe_error_message=?,attempt_count=attempt_count+1,updated_at=? WHERE job_id=?",status.name(),code,message,ts(at),jobId);}
    @Override @Transactional public void replaceChunkMetadata(String versionId,List<com.jijing.fund.knowledge.domain.DocumentChunk>chunks,String artifact,Instant at){jdbc.update("DELETE FROM knowledge_document_chunk_metadata WHERE version_id=?",versionId);for(var c:chunks)jdbc.update("""
            INSERT INTO knowledge_document_chunk_metadata(chunk_id,version_id,chunk_order,page_start,page_end,heading_path,token_count,content_sha256,artifact_storage_key,embedding_status,indexed_status,created_at)
            VALUES(?,?,?,?,?,?,?,?,?,'PENDING','PENDING',?)
            """,c.chunkId(),versionId,c.chunkOrder(),c.pageStart(),c.pageEnd(),c.headingPath(),c.tokenCount(),c.contentSha256(),artifact,ts(at));}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception ex){return "[]";}}
    private Timestamp ts(Instant value){return Timestamp.from(value);}
}
