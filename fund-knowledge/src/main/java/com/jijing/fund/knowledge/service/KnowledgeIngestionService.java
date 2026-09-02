package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.port.*;
import java.time.Clock;
import java.util.Set;

/** Fast registration boundary. Parsing and indexing are deliberately performed by a worker. */
public final class KnowledgeIngestionService implements KnowledgeIngestionUseCase {
    private static final long MAX_SIZE = 50L * 1024 * 1024;
    private final DocumentMetadataRepository repository;
    private final RawDocumentStore rawStore;
    private final Clock clock;

    public KnowledgeIngestionService(DocumentMetadataRepository repository, RawDocumentStore rawStore, Clock clock) {
        this.repository = repository; this.rawStore = rawStore; this.clock = clock;
    }

    @Override public DocumentRegistrationResult ingest(RegisterDocumentCommand command) {
        validate(command);
        String hash = KnowledgeHash.sha256(command.content());
        String storageKey = rawStore.store(hash, command.originalFileName(), command.content());
        return repository.register(command, hash, storageKey, clock.instant());
    }

    private void validate(RegisterDocumentCommand c) {
        if (c == null || c.title() == null || c.title().isBlank() || c.documentType() == null
                || c.sourceName() == null || c.sourceName().isBlank()) throw new IllegalArgumentException("title, documentType and sourceName are required");
        if ((c.externalDocumentId() == null || c.externalDocumentId().isBlank()) && c.sourceUri() == null) throw new IllegalArgumentException("externalDocumentId or sourceUri is required for version idempotency");
        if (c.content() == null || c.content().length == 0 || c.content().length > MAX_SIZE) throw new IllegalArgumentException("document size must be between 1 byte and 50 MB");
        if (c.fundCodes().stream().anyMatch(code -> !code.matches("\\d{6}"))) throw new IllegalArgumentException("fundCode must be 6 digits");
        if (!Set.of("application/pdf", "text/html", "text/plain").contains(c.contentType())) throw new IllegalArgumentException("Unsupported contentType");
    }
}
