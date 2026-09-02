package com.jijing.fund.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;

@TableName("fund")
public class FundEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String fundCode;
    private String fundName;
    private String fundType;
    private String managementCompany;
    private String fundManager;
    private LocalDate establishedDate;
    private Boolean enabled;
    private String dataSource;
    private Instant sourceUpdatedAt;
    private Instant collectedAt;
    private Long dataRevision;
    private LocalDate latestNavDate;
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getFundCode() { return fundCode; } public void setFundCode(String v) { fundCode = v; }
    public String getFundName() { return fundName; } public void setFundName(String v) { fundName = v; }
    public String getFundType() { return fundType; } public void setFundType(String v) { fundType = v; }
    public String getManagementCompany() { return managementCompany; } public void setManagementCompany(String v) { managementCompany = v; }
    public String getFundManager() { return fundManager; } public void setFundManager(String v) { fundManager = v; }
    public LocalDate getEstablishedDate() { return establishedDate; } public void setEstablishedDate(LocalDate v) { establishedDate = v; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean v) { enabled = v; }
    public String getDataSource() { return dataSource; } public void setDataSource(String v) { dataSource = v; }
    public Instant getSourceUpdatedAt() { return sourceUpdatedAt; } public void setSourceUpdatedAt(Instant v) { sourceUpdatedAt = v; }
    public Instant getCollectedAt() { return collectedAt; } public void setCollectedAt(Instant v) { collectedAt = v; }
    public Long getDataRevision() { return dataRevision; } public void setDataRevision(Long v) { dataRevision = v; }
    public LocalDate getLatestNavDate() { return latestNavDate; } public void setLatestNavDate(LocalDate v) { latestNavDate = v; }
}
