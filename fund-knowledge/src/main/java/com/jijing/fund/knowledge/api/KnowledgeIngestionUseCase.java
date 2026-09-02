package com.jijing.fund.knowledge.api;

public interface KnowledgeIngestionUseCase {
    DocumentRegistrationResult ingest(RegisterDocumentCommand command);
}
