package com.jijing.fund.knowledge.port;

import com.jijing.fund.knowledge.api.KnowledgeSearchQuery;
import com.jijing.fund.knowledge.domain.IndexedChunk;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.util.List;

/**
 * 切片的词法和向量索引。无命中时两个检索方法应返回空列表，不要用异常表示没找到。
 * 返回 null 会被融合器当成该路无命中。检索不在索引层剔除缺少来源 URI 或标题的切片。
 */
public interface DocumentSearchIndex {
    /**
     * 当前索引实现的版本名，写入已就绪的文档版本。
     */
    String indexVersion();

    /**
     * 写入一批切片。激活前这些切片不应被检索到；何时可见由实现和 {@link #activate} 约定。
     */
    void index(List<IndexedChunk> chunks);

    /**
     * 把指定文档的这个版本标成可检索，并替换该文档先前的生效版本。
     */
    void activate(String documentId, String versionId);

    /**
     * 词法检索。没有命中时返回空列表。
     */
    List<RetrievedChunk> lexicalSearch(KnowledgeSearchQuery query, int topK);

    /**
     * 向量检索。没有命中时返回空列表。查询向量由调用方提供。
     */
    List<RetrievedChunk> vectorSearch(KnowledgeSearchQuery query, float[] queryEmbedding, int topK);
}
