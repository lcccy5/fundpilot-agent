package com.jijing.fund.knowledge.api;
import java.time.Instant;
import java.util.Map;
public record IndexRebuildView(String rebuildId,String sourceAlias,String previousIndex,String targetIndex,
        String embeddingVersion,String chunkingVersion,String status,Long expectedChunkCount,long indexedChunkCount,
        Map<String,Object>validationReport,String errorCode,Instant createdAt,Instant completedAt){}
