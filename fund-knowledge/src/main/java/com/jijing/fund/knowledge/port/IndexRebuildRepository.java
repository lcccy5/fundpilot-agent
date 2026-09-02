package com.jijing.fund.knowledge.port;
import com.jijing.fund.knowledge.api.IndexRebuildView;
import java.time.Instant;
import java.util.Map;
public interface IndexRebuildRepository {
    void create(String id,String alias,String previous,String target,String embeddingVersion,String chunkingVersion,Instant now);
    void ready(String id,long expected,long indexed,Map<String,Object>report,Instant now);
    void status(String id,String expectedStatus,String status,Instant now);
    void fail(String id,String code,Map<String,Object>report,Instant now);
    IndexRebuildView find(String id);
}
