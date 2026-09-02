package com.jijing.fund.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;

@TableName("fund_sync_record")
public class FundSyncRecordEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String fundCode; private String sourceName; private String syncType; private LocalDate dateRangeStart;
    private LocalDate dateRangeEnd; private Integer requestedCount; private Integer savedCount; private String syncStatus;
    private String errorCode; private String errorMessage; private Instant syncedAt; private Instant startedAt; private Instant finishedAt;
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getFundCode() { return fundCode; } public void setFundCode(String v) { fundCode = v; }
    public String getSourceName() { return sourceName; } public void setSourceName(String v) { sourceName = v; }
    public String getSyncType() { return syncType; } public void setSyncType(String v) { syncType = v; }
    public LocalDate getDateRangeStart() { return dateRangeStart; } public void setDateRangeStart(LocalDate v) { dateRangeStart = v; }
    public LocalDate getDateRangeEnd() { return dateRangeEnd; } public void setDateRangeEnd(LocalDate v) { dateRangeEnd = v; }
    public Integer getRequestedCount() { return requestedCount; } public void setRequestedCount(Integer v) { requestedCount = v; }
    public Integer getSavedCount() { return savedCount; } public void setSavedCount(Integer v) { savedCount = v; }
    public String getSyncStatus() { return syncStatus; } public void setSyncStatus(String v) { syncStatus = v; }
    public String getErrorCode() { return errorCode; } public void setErrorCode(String v) { errorCode = v; }
    public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String v) { errorMessage = v; }
    public Instant getSyncedAt() { return syncedAt; } public void setSyncedAt(Instant v) { syncedAt = v; }
    public Instant getStartedAt() { return startedAt; } public void setStartedAt(Instant v) { startedAt = v; }
    public Instant getFinishedAt() { return finishedAt; } public void setFinishedAt(Instant v) { finishedAt = v; }
}
