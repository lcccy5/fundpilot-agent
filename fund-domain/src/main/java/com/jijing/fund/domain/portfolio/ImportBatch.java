package com.jijing.fund.domain.portfolio;

import com.jijing.fund.domain.identity.UserId;
import java.time.Instant;
import java.util.List;

public record ImportBatch(String batchId,PortfolioId portfolioId,UserId ownerUserId,String fileSha256,String fileName,
                          ImportBatchStatus status,int totalRows,int validRows,int invalidRows,Instant createdAt,
                          Instant committedAt,List<ImportRow> rows) {
    public ImportBatch {
        rows=rows==null?List.of():List.copyOf(rows);
    }
}
