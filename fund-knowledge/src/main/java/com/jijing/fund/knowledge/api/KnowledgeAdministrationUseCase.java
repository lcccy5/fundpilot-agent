package com.jijing.fund.knowledge.api;

public interface KnowledgeAdministrationUseCase {
    KnowledgeDocumentView document(String documentId);
    KnowledgeVersionView version(String versionId);
    KnowledgeJobView job(String jobId);
    KnowledgeJobView retry(String jobId);
}
