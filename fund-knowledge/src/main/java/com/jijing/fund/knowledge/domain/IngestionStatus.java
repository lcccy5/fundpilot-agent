package com.jijing.fund.knowledge.domain;

import java.util.Set;

public enum IngestionStatus {
    REGISTERED, FETCHING, FETCHED, PARSING, PARSED, CHUNKING, CHUNKED,
    EMBEDDING, INDEXING, READY, FAILED_RETRYABLE, FAILED_FINAL, OCR_REQUIRED, SUPERSEDED;

    public boolean canTransitionTo(IngestionStatus next) {
        return switch (this) {
            case REGISTERED -> next == FETCHING || next == FETCHED || failure(next);
            case FETCHING -> next == FETCHED || failure(next);
            case FETCHED -> next == PARSING || failure(next);
            case PARSING -> Set.of(PARSED, OCR_REQUIRED, FAILED_RETRYABLE, FAILED_FINAL).contains(next);
            case PARSED -> next == CHUNKING || failure(next);
            case CHUNKING -> next == CHUNKED || failure(next);
            case CHUNKED -> next == EMBEDDING || failure(next);
            case EMBEDDING -> next == INDEXING || failure(next);
            case INDEXING -> next == READY || failure(next);
            case FAILED_RETRYABLE -> Set.of(FETCHING, PARSING, CHUNKING, EMBEDDING, INDEXING, FAILED_FINAL).contains(next);
            case READY -> next == SUPERSEDED;
            case FAILED_FINAL, OCR_REQUIRED, SUPERSEDED -> false;
        };
    }

    private static boolean failure(IngestionStatus next) {
        return next == FAILED_RETRYABLE || next == FAILED_FINAL;
    }
}
