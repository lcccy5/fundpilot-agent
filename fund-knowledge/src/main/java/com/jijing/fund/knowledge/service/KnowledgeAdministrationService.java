package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.port.KnowledgeJobRepository;
import java.time.Clock;

public final class KnowledgeAdministrationService implements KnowledgeAdministrationUseCase {
    private final KnowledgeJobRepository repository; private final Clock clock;
    public KnowledgeAdministrationService(KnowledgeJobRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
    public KnowledgeDocumentView document(String id){return repository.findDocument(id);}
    public KnowledgeVersionView version(String id){return repository.findVersion(id);}
    public KnowledgeJobView job(String id){return repository.findJob(id);}
    public KnowledgeJobView retry(String id){return repository.retry(id,clock.instant());}
}
