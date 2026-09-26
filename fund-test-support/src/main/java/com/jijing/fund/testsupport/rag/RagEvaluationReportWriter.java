package com.jijing.fund.testsupport.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 把评测报告写成格式化 JSON。
 * 先写临时文件再替换目标，避免读者看到写到一半的报告。
 */
public final class RagEvaluationReportWriter {
    private final ObjectMapper mapper;

    /**
     * 保存用于序列化报告的映射器。
     * 传入 null 时构造成功，写文件时才会失败。
     */
    public RagEvaluationReportWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 把报告写到目标路径，必要时创建父目录。
     * 父目录建不出来、磁盘满、序列化失败，或替换目标文件失败时，抛出 IllegalStateException，不保留半成品为最终文件名。
     * 文件系统不支持原子移动时退回普通替换；中途失败留下的临时文件不会在这里删除。
     */
    public void write(Path target, RagEvaluationRunner.Report report) {
        try {
            Path absolute = target.toAbsolutePath().normalize();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            Path temp = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), report);
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write RAG evaluation report", e);
        }
    }
}
