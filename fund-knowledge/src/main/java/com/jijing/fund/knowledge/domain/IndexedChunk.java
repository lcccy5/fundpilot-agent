package com.jijing.fund.knowledge.domain;

/**
 * 准备写入索引的切片及其向量。向量为 null 时保存成空数组。
 * 空数组不是「无命中」，只表示这条切片没有可用向量；检索无命中由搜索端口返回空列表表达。
 */
public record IndexedChunk(DocumentChunk chunk, float[] embedding, String embeddingVersion) {
    /**
     * 复制向量，避免调用方随后改写传入的数组。null 收成空数组。
     */
    public IndexedChunk {
        embedding = embedding == null ? new float[0] : embedding.clone();
    }

    /**
     * 再复制一份向量，避免读取方改到记录内部的数组。
     */
    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}
