package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.api.IndexRebuildView;
import com.jijing.fund.knowledge.api.KnowledgeIndexGovernanceUseCase;
import com.jijing.fund.knowledge.port.IndexGovernanceGateway;
import com.jijing.fund.knowledge.port.IndexRebuildRepository;
import java.time.Clock;
import java.util.UUID;

/**
 * 发起索引重建，并在条数一致时允许切换或回滚别名。
 * <p>
 * 重建本身不检索文档。期望条数和实际条数不一致时调用仓库的失败记录，然后仍返回查找结果，不抛异常。
 * 激活要求状态字符串恰好是 {@code READY_TO_ACTIVATE}，回滚要求恰好是 {@code ACTIVE}，否则抛出
 * {@link IllegalStateException}。写别名由读别名把子串 {@code _read} 换成 {@code _write} 得到。
 */
public final class KnowledgeIndexGovernanceService implements KnowledgeIndexGovernanceUseCase {
    private final IndexGovernanceGateway gateway;
    private final IndexRebuildRepository repository;
    private final Clock clock;
    private final String embeddingVersion;
    private final String chunkingVersion;

    /**
     * @param embeddingVersion 记入重建记录的向量版本，本类不据此重新向量化
     * @param chunkingVersion 记入重建记录的切块版本
     */
    public KnowledgeIndexGovernanceService(
            IndexGovernanceGateway gateway,
            IndexRebuildRepository repository,
            Clock clock,
            String embeddingVersion,
            String chunkingVersion) {
        this.gateway = gateway;
        this.repository = repository;
        this.clock = clock;
        this.embeddingVersion = embeddingVersion;
        this.chunkingVersion = chunkingVersion;
    }

    /**
     * 新建一次重建。网关抛出的运行时异常原样传播。条数不一致时错误码 {@code INDEX_COUNT_MISMATCH} 只写入仓库。
     */
    @Override
    public IndexRebuildView create() {
        String id = UUID.randomUUID().toString();
        IndexGovernanceGateway.PreparedIndex prepared = gateway.rebuild(id);
        repository.create(
                id,
                prepared.readAlias(),
                prepared.previousIndex(),
                prepared.targetIndex(),
                embeddingVersion,
                chunkingVersion,
                clock.instant());
        if (prepared.expectedCount() != prepared.indexedCount()) {
            repository.fail(id, "INDEX_COUNT_MISMATCH", prepared.validation(), clock.instant());
        } else {
            repository.ready(
                    id,
                    prepared.expectedCount(),
                    prepared.indexedCount(),
                    prepared.validation(),
                    clock.instant());
        }
        return repository.find(id);
    }

    /**
     * 按标识读取重建记录。不存在时的异常由仓库决定。
     */
    @Override
    public IndexRebuildView get(String id) {
        return repository.find(id);
    }

    /**
     * 把就绪重建切到目标索引。状态不是 {@code READY_TO_ACTIVATE} 时拒绝，不会调用网关。
     */
    @Override
    public IndexRebuildView activate(String id) {
        IndexRebuildView view = repository.find(id);
        if (!"READY_TO_ACTIVATE".equals(view.status())) {
            throw new IllegalStateException("Index rebuild is not ready to activate");
        }
        gateway.activate(
                view.sourceAlias(),
                view.sourceAlias().replace("_read", "_write"),
                view.previousIndex(),
                view.targetIndex());
        repository.status(id, "READY_TO_ACTIVATE", "ACTIVE", clock.instant());
        return repository.find(id);
    }

    /**
     * 把当前生效的重建切回上一版索引。状态不是 {@code ACTIVE} 时拒绝，不会调用网关。
     */
    @Override
    public IndexRebuildView rollback(String id) {
        IndexRebuildView view = repository.find(id);
        if (!"ACTIVE".equals(view.status())) {
            throw new IllegalStateException("Only active rebuild can be rolled back");
        }
        gateway.rollback(
                view.sourceAlias(),
                view.sourceAlias().replace("_read", "_write"),
                view.targetIndex(),
                view.previousIndex());
        repository.status(id, "ACTIVE", "ROLLED_BACK", clock.instant());
        return repository.find(id);
    }
}
