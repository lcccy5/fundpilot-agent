package com.jijing.fund.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.event.DomainEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTransactionalOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public JdbcTransactionalOutbox(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    @Transactional public void appendWithBusiness(Runnable business,DomainEvent event){
        business.run();
        append(event);
    }

    @Transactional public void append(DomainEvent event){
        jdbc.update("""
                INSERT IGNORE INTO outbox_event(event_id,event_type,aggregate_type,aggregate_id,owner_user_id,schema_version,payload_json,evidence_ids_json,deduplication_key,status,attempts,occurred_at)
                VALUES(?,?,?,?,?,?,CAST(? AS JSON),CAST(? AS JSON),?,?,0,?)
                """,event.eventId(),event.eventType(),event.aggregateType(),event.aggregateId(),event.ownerUserId(),event.schemaVersion(),json(event.payload()),json(event.evidenceIds()),event.deduplicationKey(),"PENDING",Timestamp.from(event.occurredAt()));
    }

    @Transactional public Optional<DomainEvent> claimPending(String workerId,Instant now){
        var rows=jdbc.query("""
                SELECT event_id,event_type,aggregate_type,aggregate_id,owner_user_id,schema_version,CAST(payload_json AS CHAR),CAST(evidence_ids_json AS CHAR),deduplication_key,occurred_at
                FROM outbox_event WHERE status IN ('PENDING','RETRY_WAIT') AND (lease_until IS NULL OR lease_until<?)
                ORDER BY occurred_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """,(rs,n)->new DomainEvent(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getTimestamp(10).toInstant(),rs.getString(6),rs.getString(9),readMap(rs.getString(7)),readList(rs.getString(8)),null),Timestamp.from(now));
        if(rows.isEmpty())return Optional.empty();
        DomainEvent event=rows.getFirst();
        jdbc.update("UPDATE outbox_event SET status='PUBLISHING',lease_owner=?,lease_until=?,attempts=attempts+1 WHERE event_id=?",workerId,Timestamp.from(now.plusSeconds(30)),event.eventId());
        return Optional.of(event);
    }

    @Transactional public boolean consumeIdempotent(String consumer,String eventId,Instant now){
        int inserted=jdbc.update("INSERT IGNORE INTO event_consumption(consumer_name,event_id,status,consumed_at) VALUES(?,?,?,?)",consumer,eventId,"CONSUMED",Timestamp.from(now));
        if(inserted==0)return false;
        jdbc.update("UPDATE outbox_event SET status='PUBLISHED',published_at=? WHERE event_id=?",Timestamp.from(now),eventId);
        return true;
    }

    @Transactional public void deadLetter(String eventId,String reason,Instant now){
        jdbc.update("INSERT INTO event_dead_letter(dead_letter_id,event_id,reason_code,detail,created_at) VALUES(?,?,?,?,?)",
                UUID.randomUUID().toString(),eventId,reason==null?"UNKNOWN":reason,null,Timestamp.from(now));
        jdbc.update("UPDATE outbox_event SET status='DEAD_LETTER',last_error_code=? WHERE event_id=?",reason,eventId);
    }

    private String json(Object value){try{return mapper.writeValueAsString(value==null?List.of():value);}catch(Exception e){return "[]";}}
    @SuppressWarnings("unchecked")
    private Map<String,Object> readMap(String raw){try{return raw==null||raw.isBlank()?Map.of():mapper.readValue(raw,Map.class);}catch(Exception e){return Map.of();}}
    @SuppressWarnings("unchecked")
    private List<String> readList(String raw){try{return raw==null||raw.isBlank()?List.of():mapper.readValue(raw,List.class);}catch(Exception e){return List.of();}}
}
