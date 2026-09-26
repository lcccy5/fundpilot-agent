package com.jijing.fund.infrastructure.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.ParsedDocument;
import com.jijing.fund.knowledge.port.KnowledgeCheckpointStore;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 把摄取中间结果写到本地 JSON。版本号只能是字母、数字和连字符，防止路径逃逸。
 * 写入先落临时文件再移动；文件系统不支持原子移动时退回普通替换。
 * 读写失败抛出非法状态，没有网络超时或连接失败。损坏的检查点文件无法恢复。
 */
public final class LocalKnowledgeCheckpointStore implements KnowledgeCheckpointStore {
    private final Path root;
    private final ObjectMapper mapper;

    /** 根目录规范化后保存，后续所有版本目录都相对它解析。 */
    public LocalKnowledgeCheckpointStore(Path root, ObjectMapper mapper) {
        this.root = root.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    /** 覆盖同一版本的解析结果。 */
    @Override
    public void saveParsed(String id, ParsedDocument value) {
        write(id, "parsed.json", value);
    }

    /** 文件缺失或 JSON 损坏时无法继续该版本。 */
    @Override
    public ParsedDocument loadParsed(String id) {
        return read(id, "parsed.json", ParsedDocument.class);
    }

    /** 覆盖同一版本的分块列表。 */
    @Override
    public void saveChunks(String id, List<DocumentChunk> value) {
        write(id, "chunks.json", value);
    }

    /** 分块文件必须能完整反序列化。 */
    @Override
    public List<DocumentChunk> loadChunks(String id) {
        return read(id, "chunks.json", new TypeReference<List<DocumentChunk>>() {
        });
    }

    /** 每个批次单独一个文件，重复保存同一批次会覆盖。 */
    @Override
    public void saveEmbeddingBatch(String id, int batch, List<float[]> value) {
        write(id, "embedding-" + batch + ".json", value);
    }

    /** 按批次号从 1 读到 count，缺任一文件则整个恢复失败。 */
    @Override
    public List<float[]> loadEmbeddingBatches(String id, int count) {
        List<float[]> all = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            all.addAll(read(id, "embedding-" + i + ".json", new TypeReference<List<float[]>>() {
            }));
        }
        return all;
    }

    /** 临时文件与目标在同一目录，便于同盘移动。原子移动不支持时仍替换目标。 */
    private void write(String id, String name, Object value) {
        try {
            Path dir = dir(id);
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, name, ".tmp");
            mapper.writeValue(temp.toFile(), value);
            Path target = dir.resolve(name);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot save ingestion checkpoint", e);
        }
    }

    /** 按具体类型读取。失败不返回空对象，避免把损坏检查点当成空文档。 */
    private <T> T read(String id, String name, Class<T> type) {
        try {
            return mapper.readValue(dir(id).resolve(name).toFile(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot restore ingestion checkpoint", e);
        }
    }

    /** 按泛型读取列表。失败语义与 {@link #read(String, String, Class)} 相同。 */
    private <T> T read(String id, String name, TypeReference<T> type) {
        try {
            return mapper.readValue(dir(id).resolve(name).toFile(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot restore ingestion checkpoint", e);
        }
    }

    /** 非法版本号或解析后离开根目录的路径都拒绝。 */
    private Path dir(String id) {
        if (id == null || !id.matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("Invalid checkpoint version id");
        }
        Path value = root.resolve(id).normalize();
        if (!value.startsWith(root)) {
            throw new IllegalArgumentException("Checkpoint path escaped root");
        }
        return value;
    }
}
