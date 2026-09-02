package com.jijing.fund.knowledge.domain;

public record IndexedChunk(DocumentChunk chunk, float[] embedding, String embeddingVersion) {
    public IndexedChunk { embedding = embedding == null ? new float[0] : embedding.clone(); }
    @Override public float[] embedding(){return embedding.clone();}
}
