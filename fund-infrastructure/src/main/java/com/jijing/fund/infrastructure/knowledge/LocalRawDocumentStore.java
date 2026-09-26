package com.jijing.fund.infrastructure.knowledge;

import com.jijing.fund.knowledge.port.RawDocumentStore;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * 按内容哈希把原文落在本地目录。路径逃出根目录时抛出 {@link SecurityException}。
 * 同一哈希已存在则直接返回相对路径，不覆盖。并发创建撞上 {@link FileAlreadyExistsException} 时也视为已写入。
 * 没有网络超时或连接失败。读失败抛出 {@code Cannot read raw document}。
 */
public final class LocalRawDocumentStore implements RawDocumentStore {
    private final Path root;

    /** 根目录会立即创建。创建失败时存储不可用，而不是拖到第一次写入。 */
    public LocalRawDocumentStore(Path root) {
        try {
            this.root = root.toAbsolutePath().normalize();
            Files.createDirectories(this.root);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot initialize document storage", ex);
        }
    }

    /**
     * 哈希前两位作为分目录。扩展名只认 pdf、html、htm、txt，其余用 {@code .bin}。
     */
    @Override
    public String store(String hash, String fileName, byte[] content) {
        try {
            String extension = extension(fileName);
            Path target = root.resolve(hash.substring(0, 2)).resolve(hash + extension).normalize();
            ensureInside(target);
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                try {
                    Files.write(target, content, StandardOpenOption.CREATE_NEW);
                } catch (FileAlreadyExistsException ignored) {
                    // 并发写入同一哈希时后到的一方沿用已落盘文件。
                }
            }
            return root.relativize(target).toString().replace('\\', '/');
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot store raw document", ex);
        }
    }

    /** 存储键被规范化后仍必须留在根目录内。 */
    @Override
    public byte[] read(String storageKey) {
        try {
            Path target = root.resolve(storageKey).normalize();
            ensureInside(target);
            return Files.readAllBytes(target);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot read raw document", ex);
        }
    }

    /** 拒绝 {@code ..} 一类逃逸，哪怕调用方拼出了看起来合法的键。 */
    private void ensureInside(Path target) {
        if (!target.startsWith(root)) {
            throw new SecurityException("Document path escapes configured storage root");
        }
    }

    /** 只看文件名后缀，不信任 Content-Type。 */
    private String extension(String fileName) {
        if (fileName == null) {
            return ".bin";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return ".pdf";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return ".html";
        }
        if (lower.endsWith(".txt")) {
            return ".txt";
        }
        return ".bin";
    }
}
