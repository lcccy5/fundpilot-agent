package com.jijing.fund.knowledge.port;

import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.ParsedDocument;
import java.util.List;

/**
 * 解析结果、切片和向量批次的检查点。
 * 恢复时若检查点缺失，应抛出消息中含 {@code CHECKPOINT} 的运行时异常，
 * 入库流程会把它定为不可重试的无效文档。消息里没有这个词时，会按普通依赖错误重试。
 */
public interface KnowledgeCheckpointStore {
    /**
     * 保存解析后的分页文本，供进程中断后从切块继续。
     */
    void saveParsed(String versionId, ParsedDocument parsed);

    /**
     * 读回解析结果。不存在时按上面的约定失败。
     */
    ParsedDocument loadParsed(String versionId);

    /**
     * 保存切块结果，供向量化中断后继续。
     */
    void saveChunks(String versionId, List<DocumentChunk> chunks);

    /**
     * 读回切片。不存在时按上面的约定失败。
     */
    List<DocumentChunk> loadChunks(String versionId);

    /**
     * 保存一批已经算好的向量。批次号从 1 起。
     */
    void saveEmbeddingBatch(String versionId, int batchNo, List<float[]> vectors);

    /**
     * 按已完成的批次数读回向量，顺序与切片一致。批次数为 0 时应返回空列表。
     */
    List<float[]> loadEmbeddingBatches(String versionId, int completedBatchCount);
}
