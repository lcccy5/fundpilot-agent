package com.jijing.fund.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code fund} 表的一行。保存基金主数据、采集来源，以及净值变更后递增的 {@code data_revision}。
 * 不映射 {@code created_at}、{@code updated_at}，读出的对象里看不到这两列。
 * 自增主键在插入前为 null。按代码查询没有命中时，映射器返回 null，不会得到字段全空的本类实例。
 * 自定义 upsert 把 null 绑定成 SQL NULL；MyBatis-Plus 生成的 INSERT/UPDATE 默认跳过 null 属性，列保持数据库默认值或原值。
 */
@TableName("fund")
public class FundEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
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

    /**
     * 读出自增主键。插入前或结果映射未回填时为 null；库中不存在“主键为 null”的行。
     */
    public Long getId() {
        return id;
    }

    /**
     * 记下自增主键。传入 null 表示尚未持久化；对已存在行执行更新时，null 主键无法定位行。
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 读出六位基金代码。列 {@code fund_code} 非空且唯一；内存里尚未赋值时返回 null。
     */
    public String getFundCode() {
        return fundCode;
    }

    /**
     * 写入基金代码。自定义 upsert 会把 null 绑定成 SQL NULL，而该列禁止 NULL，数据库会拒绝插入。
     */
    public void setFundCode(String v) {
        fundCode = v;
    }

    /**
     * 读出基金名称。列非空；映射对象尚未赋值时返回 null，领域对象会因此无法构造。
     */
    public String getFundName() {
        return fundName;
    }

    /**
     * 写入基金名称。自定义 upsert 会把 null 绑定成 SQL NULL，该列禁止 NULL，插入失败。
     */
    public void setFundName(String v) {
        fundName = v;
    }

    /**
     * 读出基金类型。列允许 NULL，没有类型时返回 null。
     */
    public String getFundType() {
        return fundType;
    }

    /**
     * 写入基金类型。null 在自定义 upsert 中保存为 SQL NULL。
     */
    public void setFundType(String v) {
        fundType = v;
    }

    /**
     * 读出管理人名称。列允许 NULL，缺失时返回 null。
     */
    public String getManagementCompany() {
        return managementCompany;
    }

    /**
     * 写入管理人。null 在自定义 upsert 中保存为 SQL NULL。
     */
    public void setManagementCompany(String v) {
        managementCompany = v;
    }

    /**
     * 读出基金经理。列允许 NULL，缺失时返回 null。
     */
    public String getFundManager() {
        return fundManager;
    }

    /**
     * 写入基金经理。null 在自定义 upsert 中保存为 SQL NULL。
     */
    public void setFundManager(String v) {
        fundManager = v;
    }

    /**
     * 读出成立日。列允许 NULL，未知成立日返回 null。
     */
    public LocalDate getEstablishedDate() {
        return establishedDate;
    }

    /**
     * 写入成立日。null 在自定义 upsert 中保存为 SQL NULL。
     */
    public void setEstablishedDate(LocalDate v) {
        establishedDate = v;
    }

    /**
     * 读出是否参与启用列表。列非空且默认 1；本字段未被 upsert 语句引用，未赋值时返回 null，不代表库中的默认值。
     */
    public Boolean getEnabled() {
        return enabled;
    }

    /**
     * 仅改变内存中的启用标记。基金 upsert 插入时写死 {@code enabled=1}，重复键更新不修改该列，因此这里传入 null 或 false 都不会写入数据库。
     */
    public void setEnabled(Boolean v) {
        enabled = v;
    }

    /**
     * 读出数据采集源名称。列非空；尚未赋值时返回 null。
     */
    public String getDataSource() {
        return dataSource;
    }

    /**
     * 写入数据源。自定义 upsert 会把 null 绑定成 SQL NULL，该列禁止 NULL，插入失败。
     */
    public void setDataSource(String v) {
        dataSource = v;
    }

    /**
     * 读出数据源侧的更新时间。列允许 NULL，源没有提供时间时返回 null。
     */
    public Instant getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    /**
     * 写入数据源更新时间。null 在自定义 upsert 中保存为 SQL NULL。
     */
    public void setSourceUpdatedAt(Instant v) {
        sourceUpdatedAt = v;
    }

    /**
     * 读出本系统采集时间。列非空；尚未赋值时返回 null。
     */
    public Instant getCollectedAt() {
        return collectedAt;
    }

    /**
     * 写入采集时间。自定义 upsert 会把 null 绑定成 SQL NULL，该列禁止 NULL，插入失败。
     */
    public void setCollectedAt(Instant v) {
        collectedAt = v;
    }

    /**
     * 读出净值数据修订号。列非空且默认 0；本字段不在 upsert 语句中，新行的库默认值不会自动出现在这个未赋值字段上，未加载时返回 null。
     */
    public Long getDataRevision() {
        return dataRevision;
    }

    /**
     * 写入修订号。基金 upsert 不包含该列，这里的值不会被插入或在重复键时更新；修订号只由单独的递增语句修改。
     */
    public void setDataRevision(Long v) {
        dataRevision = v;
    }

    /**
     * 读出已入库的最新净值日。列允许 NULL，还没有净值时返回 null。
     */
    public LocalDate getLatestNavDate() {
        return latestNavDate;
    }

    /**
     * 写入最新净值日。基金 upsert 不包含该列；要落库必须走修订号递增语句，传入 null 会把该列更新成 SQL NULL。
     */
    public void setLatestNavDate(LocalDate v) {
        latestNavDate = v;
    }
}
