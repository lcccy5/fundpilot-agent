package com.jijing.fund.knowledge.api;

/**
 * 管理端查询文档、版本和入库任务，并重试失败任务。
 * 找不到记录或任务状态不允许重试时，异常由仓库实现抛出，本入口不定义额外的空结果。
 */
public interface KnowledgeAdministrationUseCase {
    /**
     * 读取文档及其版本。
     */
    KnowledgeDocumentView document(String documentId);

    /**
     * 读取一个版本。
     */
    KnowledgeVersionView version(String versionId);

    /**
     * 读取一条入库任务。
     */
    KnowledgeJobView job(String jobId);

    /**
     * 重试一条失败任务并返回更新后的任务视图。
     */
    KnowledgeJobView retry(String jobId);
}
