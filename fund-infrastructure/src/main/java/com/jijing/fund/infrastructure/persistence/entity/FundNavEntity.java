package com.jijing.fund.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.*;

@TableName("fund_nav")
public class FundNavEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String fundCode; private LocalDate navDate; private BigDecimal unitNav; private BigDecimal accumulatedNav; private BigDecimal adjustedNav;
    private String navStatus; private String dataSource; private Instant sourceUpdatedAt; private Instant collectedAt;
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getFundCode() { return fundCode; } public void setFundCode(String v) { fundCode = v; }
    public LocalDate getNavDate() { return navDate; } public void setNavDate(LocalDate v) { navDate = v; }
    public BigDecimal getUnitNav() { return unitNav; } public void setUnitNav(BigDecimal v) { unitNav = v; }
    public BigDecimal getAccumulatedNav() { return accumulatedNav; } public void setAccumulatedNav(BigDecimal v) { accumulatedNav = v; }
    public BigDecimal getAdjustedNav() { return adjustedNav; } public void setAdjustedNav(BigDecimal v) { adjustedNav = v; }
    public String getNavStatus() { return navStatus; } public void setNavStatus(String v) { navStatus = v; }
    public String getDataSource() { return dataSource; } public void setDataSource(String v) { dataSource = v; }
    public Instant getSourceUpdatedAt() { return sourceUpdatedAt; } public void setSourceUpdatedAt(Instant v) { sourceUpdatedAt = v; }
    public Instant getCollectedAt() { return collectedAt; } public void setCollectedAt(Instant v) { collectedAt = v; }
}
