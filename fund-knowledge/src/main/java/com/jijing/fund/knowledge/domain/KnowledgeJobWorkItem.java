package com.jijing.fund.knowledge.domain;

import java.time.LocalDate;
import java.util.Set;

/**
 * 工人领取到的一条入库任务。来源名称和来源 URI 会原样进入切块上下文，允许为 null。
 * {@code lastCompletedStep} 为空或不是状态名时，处理流程从 {@link IngestionStatus#REGISTERED} 重做。
 * {@code attemptCount} 参与失败分类：达到 3 次后普通依赖错误不再重试。
 * 基金代码为 null 时收成空集。
 */
public record KnowledgeJobWorkItem(
        String jobId,
        String documentId,
        String versionId,
        String storageKey,
        String originalFileName,
        String contentType,
        String title,
        FundDocumentType documentType,
        LocalDate publishedDate,
        Set<String> fundCodes,
        String sourceName,
        String sourceUri,
        int attemptCount,
        String lastCompletedStep,
        int embeddedBatchNo) {
    /**
     * 复制基金代码。null 视为这篇文档没有关联基金。
     */
    public KnowledgeJobWorkItem {
        fundCodes = fundCodes == null ? Set.of() : Set.copyOf(fundCodes);
    }
}
