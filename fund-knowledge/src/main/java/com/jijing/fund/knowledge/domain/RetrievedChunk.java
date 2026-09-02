package com.jijing.fund.knowledge.domain;

import java.util.Set;

public record RetrievedChunk(DocumentChunk chunk, double score, int rank, Set<String> channels) {
    public RetrievedChunk { channels = channels == null ? Set.of() : Set.copyOf(channels); }
}
