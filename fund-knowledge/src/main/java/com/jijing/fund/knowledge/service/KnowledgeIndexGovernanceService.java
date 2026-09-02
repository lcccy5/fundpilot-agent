package com.jijing.fund.knowledge.service;
import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.port.*;
import java.time.*;
import java.util.*;
public final class KnowledgeIndexGovernanceService implements KnowledgeIndexGovernanceUseCase {
 private final IndexGovernanceGateway gateway;private final IndexRebuildRepository repository;private final Clock clock;private final String embeddingVersion,chunkingVersion;
 public KnowledgeIndexGovernanceService(IndexGovernanceGateway gateway,IndexRebuildRepository repository,Clock clock,String embeddingVersion,String chunkingVersion){this.gateway=gateway;this.repository=repository;this.clock=clock;this.embeddingVersion=embeddingVersion;this.chunkingVersion=chunkingVersion;}
 public IndexRebuildView create(){String id=UUID.randomUUID().toString();try{var p=gateway.rebuild(id);repository.create(id,p.readAlias(),p.previousIndex(),p.targetIndex(),embeddingVersion,chunkingVersion,clock.instant());if(p.expectedCount()!=p.indexedCount()){repository.fail(id,"INDEX_COUNT_MISMATCH",p.validation(),clock.instant());}else repository.ready(id,p.expectedCount(),p.indexedCount(),p.validation(),clock.instant());return repository.find(id);}catch(RuntimeException e){throw e;}}
 public IndexRebuildView get(String id){return repository.find(id);}
 public IndexRebuildView activate(String id){IndexRebuildView v=repository.find(id);if(!"READY_TO_ACTIVATE".equals(v.status()))throw new IllegalStateException("Index rebuild is not ready to activate");gateway.activate(v.sourceAlias(),v.sourceAlias().replace("_read","_write"),v.previousIndex(),v.targetIndex());repository.status(id,"READY_TO_ACTIVATE","ACTIVE",clock.instant());return repository.find(id);}
 public IndexRebuildView rollback(String id){IndexRebuildView v=repository.find(id);if(!"ACTIVE".equals(v.status()))throw new IllegalStateException("Only active rebuild can be rolled back");gateway.rollback(v.sourceAlias(),v.sourceAlias().replace("_read","_write"),v.targetIndex(),v.previousIndex());repository.status(id,"ACTIVE","ROLLED_BACK",clock.instant());return repository.find(id);}
}
