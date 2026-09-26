package com.jijing.fund.knowledge.port;

import java.util.List;

/**
 * 文本向量端口。入库按批调用 {@link #embed}，检索只对查询调用 {@link #embedQuery}。
 * 返回向量的条数必须和输入文本条数一致，否则入库流程抛出 {@code EMBEDDING_COUNT_MISMATCH} 并按可重试依赖失败处理，
 * 而不是维度终态。维度不匹配应由实现在消息中带上 {@code DIMENSION}，流程才会把它定为不可重试。
 */
public interface DocumentEmbeddingPort {
    /**
     * 当前向量模型版本，写入索引记录和版本元数据。
     */
    String version();

    /**
     * 为一批切片正文生成向量。空列表应返回空列表。返回 null 或条数不一致会导致入库失败。
     */
    List<float[]> embed(List<String> texts);

    /**
     * 为查询文本生成向量。空查询不会到达这里，检索实现会先拒绝空白和超长查询。
     */
    float[] embedQuery(String text);
}
