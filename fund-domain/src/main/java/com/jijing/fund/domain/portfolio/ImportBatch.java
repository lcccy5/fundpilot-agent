package com.jijing.fund.domain.portfolio;

import com.jijing.fund.domain.identity.UserId;
import java.time.Instant;
import java.util.List;

/**
 * 一次交易文件导入的批次，记录文件指纹、预览/提交状态、行数统计和逐行校验结果，
 * 用于“先预览、再提交”的两阶段导入。
 */
public record ImportBatch(String batchId, PortfolioId portfolioId, UserId ownerUserId, String fileSha256, String fileName,
                          ImportBatchStatus status, int totalRows, int validRows, int invalidRows, Instant createdAt,
                          Instant committedAt, List<ImportRow> rows) {
    /**
     * 规范化导入行：null 视为没有行，否则拷贝为不可变列表，调用方之后修改原列表不会影响批次；行列表含 null 元素时抛出 NullPointerException。
     * 其余字段不校验，行数统计与实际行数不一致、负数行数也不会被拒绝。
     */
    public ImportBatch {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
