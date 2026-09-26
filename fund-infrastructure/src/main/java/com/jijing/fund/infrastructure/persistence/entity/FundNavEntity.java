package com.jijing.fund.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code fund_nav} 表的一行，按 {@code (fund_code, nav_date)} 唯一。
 * 保存单位净值、累计净值、复权净值、净值状态和采集来源。不映射 {@code created_at}、{@code updated_at}。
 * 查询没有命中时映射器返回 null 或空列表，不会制造字段全空的实例。
 * 批量 upsert 把 null 属性绑定成 SQL NULL；重复键只覆盖净值、状态和数据源，不回写两个时间戳。
 */
@TableName("fund_nav")
public class FundNavEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fundCode;
    private LocalDate navDate;
    private BigDecimal unitNav;
    private BigDecimal accumulatedNav;
    private BigDecimal adjustedNav;
    private String navStatus;
    private String dataSource;
    private Instant sourceUpdatedAt;
    private Instant collectedAt;

    /**
     * 读出自增主键。插入前为 null；业务身份是基金代码加净值日，主键不参与冲突判断。
     */
    public Long getId() {
        return id;
    }

    /**
     * 记下自增主键。传入 null 表示交给数据库生成。批量 upsert 语句不写 id 列。
     */
    public void setId(Long v) {
        id = v;
    }

    /**
     * 读出基金代码。列非空，并与净值日组成唯一键；尚未赋值时返回 null。
     */
    public String getFundCode() {
        return fundCode;
    }

    /**
     * 写入基金代码。null 会绑定成 SQL NULL，该列禁止 NULL，批量插入失败。
     */
    public void setFundCode(String v) {
        fundCode = v;
    }

    /**
     * 读出净值日期。列非空；尚未赋值时返回 null。
     */
    public LocalDate getNavDate() {
        return navDate;
    }

    /**
     * 写入净值日期。null 会绑定成 SQL NULL，该列禁止 NULL，批量插入失败。
     */
    public void setNavDate(LocalDate v) {
        navDate = v;
    }

    /**
     * 读出单位净值。列非空；尚未赋值时返回 null。
     */
    public BigDecimal getUnitNav() {
        return unitNav;
    }

    /**
     * 写入单位净值。null 会绑定成 SQL NULL，该列禁止 NULL，插入失败；重复键更新同样会把该列写成 NULL 并失败。
     */
    public void setUnitNav(BigDecimal v) {
        unitNav = v;
    }

    /**
     * 读出累计净值。列允许 NULL，数据源未提供时返回 null。
     */
    public BigDecimal getAccumulatedNav() {
        return accumulatedNav;
    }

    /**
     * 写入累计净值。null 在插入和重复键更新时都保存为 SQL NULL，会清掉原先的累计净值。
     */
    public void setAccumulatedNav(BigDecimal v) {
        accumulatedNav = v;
    }

    /**
     * 读出复权净值。列允许 NULL，未计算或未披露时返回 null。
     */
    public BigDecimal getAdjustedNav() {
        return adjustedNav;
    }

    /**
     * 写入复权净值。null 在插入和重复键更新时都保存为 SQL NULL。
     */
    public void setAdjustedNav(BigDecimal v) {
        adjustedNav = v;
    }

    /**
     * 读出净值状态的枚举名。列非空，默认 {@code CONFIRMED}；本对象尚未赋值时返回 null，领域转换会因此失败。
     */
    public String getNavStatus() {
        return navStatus;
    }

    /**
     * 写入净值状态名。null 会绑定成 SQL NULL，该列禁止 NULL，插入或重复键更新失败。无法识别的名字要到领域转换时才会暴露。
     */
    public void setNavStatus(String v) {
        navStatus = v;
    }

    /**
     * 读出数据源名称。列非空；尚未赋值时返回 null。
     */
    public String getDataSource() {
        return dataSource;
    }

    /**
     * 写入数据源。null 会绑定成 SQL NULL，该列禁止 NULL，插入或重复键更新失败。
     */
    public void setDataSource(String v) {
        dataSource = v;
    }

    /**
     * 读出数据源侧更新时间。列允许 NULL。重复键更新语句不包含该列，所以库中的值可能与后一次采集不一致，本字段只反映当前加载到的值。
     */
    public Instant getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    /**
     * 写入数据源更新时间。仅插入语句绑定该列，null 保存为 SQL NULL；同一代码和日期再次 upsert 时这条时间保持原值。
     */
    public void setSourceUpdatedAt(Instant v) {
        sourceUpdatedAt = v;
    }

    /**
     * 读出本系统采集时间。列非空。重复键更新不包含该列，再次采集后库中仍可能是第一次的采集时间。
     */
    public Instant getCollectedAt() {
        return collectedAt;
    }

    /**
     * 写入采集时间。仅插入语句绑定该列，null 会因非空约束失败；重复键路径不更新该列。
     */
    public void setCollectedAt(Instant v) {
        collectedAt = v;
    }
}
