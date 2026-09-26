package com.jijing.fund.agent.api;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 一条可回溯的证据引用，覆盖净值区间和文档片段两种来源。
 * 文档字段为空表示这是一条非文档证据；本类型不核对日期先后或页码范围。
 */
public record EvidenceReference(
        String evidenceId,
        String evidenceType,
        String fundCode,
        LocalDate actualStartDate,
        LocalDate actualEndDate,
        String navBasis,
        String dataSource,
        String dataVersion,
        String algorithmVersion,
        Instant collectedAt,
        String documentId,
        String versionId,
        String chunkId,
        String documentTitle,
        LocalDate publishedDate,
        Integer pageStart,
        Integer pageEnd,
        String headingPath,
        String excerpt,
        String sourceUri) {

    /**
     * 构造一条只有净值或指标区间、没有文档定位的证据。
     * 文档相关字段全部为空；不校验基金代码或证据标识。
     */
    public EvidenceReference(
            String evidenceId,
            String evidenceType,
            String fundCode,
            LocalDate actualStartDate,
            LocalDate actualEndDate,
            String navBasis,
            String dataSource,
            String dataVersion,
            String algorithmVersion,
            Instant collectedAt) {
        this(evidenceId, evidenceType, fundCode, actualStartDate, actualEndDate, navBasis, dataSource, dataVersion,
                algorithmVersion, collectedAt, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 构造一条基金文档片段证据，类型固定为文档。
     * 不校验页码是否颠倒，也不拒绝空摘录；版本号同时写入数据版本字段。
     */
    public static EvidenceReference document(
            String evidenceId,
            String fundCode,
            String dataSource,
            String documentId,
            String versionId,
            String chunkId,
            String title,
            LocalDate publishedDate,
            int pageStart,
            int pageEnd,
            String heading,
            String excerpt,
            String sourceUri) {
        return new EvidenceReference(
                evidenceId,
                "FUND_DOCUMENT",
                fundCode,
                null,
                null,
                null,
                dataSource,
                versionId,
                null,
                null,
                documentId,
                versionId,
                chunkId,
                title,
                publishedDate,
                pageStart,
                pageEnd,
                heading,
                excerpt,
                sourceUri);
    }
}
