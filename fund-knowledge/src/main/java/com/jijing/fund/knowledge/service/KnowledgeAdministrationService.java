package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.api.KnowledgeAdministrationUseCase;
import com.jijing.fund.knowledge.api.KnowledgeDocumentView;
import com.jijing.fund.knowledge.api.KnowledgeJobView;
import com.jijing.fund.knowledge.api.KnowledgeVersionView;
import com.jijing.fund.knowledge.port.KnowledgeJobRepository;
import java.time.Clock;

/**
 * 文档、版本和入库任务的只读查询，以及失败任务的重试入口。
 * 不存在、状态冲突和是否允许重试都由仓库决定，本类不补充校验。
 */
public final class KnowledgeAdministrationService implements KnowledgeAdministrationUseCase {
    private final KnowledgeJobRepository repository;
    private final Clock clock;

    /**
     * @param repository 读取视图并执行重试
     * @param clock 传给仓库的重试时间
     */
    public KnowledgeAdministrationService(KnowledgeJobRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 按文档标识读取文档及其版本列表。
     */
    @Override
    public KnowledgeDocumentView document(String id) {
        return repository.findDocument(id);
    }

    /**
     * 按版本标识读取一个版本。
     */
    @Override
    public KnowledgeVersionView version(String id) {
        return repository.findVersion(id);
    }

    /**
     * 按任务标识读取入库任务。
     */
    @Override
    public KnowledgeJobView job(String id) {
        return repository.findJob(id);
    }

    /**
     * 请求仓库重试该任务，并把当前时钟交给仓库。
     */
    @Override
    public KnowledgeJobView retry(String id) {
        return repository.retry(id, clock.instant());
    }
}
