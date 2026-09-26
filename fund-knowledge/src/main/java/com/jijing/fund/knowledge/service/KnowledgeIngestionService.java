package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.api.DocumentRegistrationResult;
import com.jijing.fund.knowledge.api.KnowledgeIngestionUseCase;
import com.jijing.fund.knowledge.api.RegisterDocumentCommand;
import com.jijing.fund.knowledge.port.DocumentMetadataRepository;
import com.jijing.fund.knowledge.port.RawDocumentStore;
import java.time.Clock;
import java.util.Set;

/**
 * 只负责受理注册的入库边界。解析、切块、向量和索引由后台任务执行。
 * <p>
 * 空正文、超过 50MB 的正文、缺少标题、文档类型、来源名称，或无法构成版本幂等键的命令，
 * 在写入仓库前被拒绝。本类不比较历史版本：每次通过校验都会先把字节写入 {@link RawDocumentStore}，
 * 再交给 {@link DocumentMetadataRepository#register}。重复入库的 {@code duplicate} 完全由仓库返回，
 * 本类原样交回；因此重复内容仍会先落一次原始字节。
 */
public final class KnowledgeIngestionService implements KnowledgeIngestionUseCase {
    private static final long MAX_SIZE = 50L * 1024 * 1024;
    private final DocumentMetadataRepository repository;
    private final RawDocumentStore rawStore;
    private final Clock clock;

    /**
     * @param repository 持久化注册结果；由它决定是否把同一来源和同一哈希视为重复
     * @param rawStore 按内容哈希保存原始字节
     * @param clock 交给仓库的注册时间
     */
    public KnowledgeIngestionService(DocumentMetadataRepository repository, RawDocumentStore rawStore, Clock clock) {
        this.repository = repository;
        this.rawStore = rawStore;
        this.clock = clock;
    }

    /**
     * 校验后保存原文并注册。校验失败时不会碰原文存储，也不会调用仓库。
     */
    @Override
    public DocumentRegistrationResult ingest(RegisterDocumentCommand command) {
        validate(command);
        String hash = KnowledgeHash.sha256(command.content());
        String storageKey = rawStore.store(hash, command.originalFileName(), command.content());
        return repository.register(command, hash, storageKey, clock.instant());
    }

    /**
     * 拒绝空文档、超长正文、不支持的类型、非法基金代码，以及缺少幂等键的命令。
     * 幂等键是非空白的外部文档号，或非 null 的来源 URI，二者至少有一个。
     * 基金代码必须是 6 位数字。内容类型只接受 {@code application/pdf}、{@code text/html}、{@code text/plain}，区分大小写。
     */
    private void validate(RegisterDocumentCommand command) {
        if (command == null
                || command.title() == null
                || command.title().isBlank()
                || command.documentType() == null
                || command.sourceName() == null
                || command.sourceName().isBlank()) {
            throw new IllegalArgumentException("title, documentType and sourceName are required");
        }
        if ((command.externalDocumentId() == null || command.externalDocumentId().isBlank()) && command.sourceUri() == null) {
            throw new IllegalArgumentException("externalDocumentId or sourceUri is required for version idempotency");
        }
        if (command.content() == null || command.content().length == 0 || command.content().length > MAX_SIZE) {
            throw new IllegalArgumentException("document size must be between 1 byte and 50 MB");
        }
        if (command.fundCodes().stream().anyMatch(code -> !code.matches("\\d{6}"))) {
            throw new IllegalArgumentException("fundCode must be 6 digits");
        }
        if (!Set.of("application/pdf", "text/html", "text/plain").contains(command.contentType())) {
            throw new IllegalArgumentException("Unsupported contentType");
        }
    }
}
