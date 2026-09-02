package com.jijing.fund.knowledge.port;
import com.jijing.fund.knowledge.domain.*;
import java.util.List;
public interface KnowledgeCheckpointStore {
    void saveParsed(String versionId,ParsedDocument parsed);
    ParsedDocument loadParsed(String versionId);
    void saveChunks(String versionId,List<DocumentChunk>chunks);
    List<DocumentChunk> loadChunks(String versionId);
    void saveEmbeddingBatch(String versionId,int batchNo,List<float[]>vectors);
    List<float[]> loadEmbeddingBatches(String versionId,int completedBatchCount);
}
