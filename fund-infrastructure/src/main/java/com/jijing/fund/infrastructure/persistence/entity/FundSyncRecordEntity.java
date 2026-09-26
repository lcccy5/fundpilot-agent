package com.jijing.fund.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code fund_sync_record} 表的一行同步审计。同一基金可以有多条记录，唯一约束只有自增主键。
 * 保存同步类型、日期区间、请求条数、落库条数、状态和错误信息。不映射 {@code created_at}。
 * {@code selectById} 没有命中时返回 null，调用方必须自己判断，本类不会表示“缺行”。
 * 通过 MyBatis-Plus 插入或按主键更新时，null 属性默认不出现在 SQL 中：可空列因此保持 NULL 或原值，不能靠把属性设为 null 来清空已写入的错误信息。
 */
@TableName("fund_sync_record")
public class FundSyncRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fundCode;
    private String sourceName;
    private String syncType;
    private LocalDate dateRangeStart;
    private LocalDate dateRangeEnd;
    private Integer requestedCount;
    private Integer savedCount;
    private String syncStatus;
    private String errorCode;
    private String errorMessage;
    private Instant syncedAt;
    private Instant startedAt;
    private Instant finishedAt;

    /**
     * 读出自增主键。{@code insert} 成功回填前为 null；调用方若把 null 拆成基本类型会抛出空指针。
     */
    public Long getId() {
        return id;
    }

    /**
     * 记下主键。传入 null 表示插入时由数据库分配。若指定一个已经存在的主键，插入会触发主键冲突。
     */
    public void setId(Long v) {
        id = v;
    }

    /**
     * 读出被同步的基金代码。列非空；尚未赋值时返回 null。
     */
    public String getFundCode() {
        return fundCode;
    }

    /**
     * 写入基金代码。null 在 MyBatis-Plus 插入时被省略，列又禁止 NULL，数据库拒绝该行。
     */
    public void setFundCode(String v) {
        fundCode = v;
    }

    /**
     * 读出数据源名称。列非空；尚未赋值时返回 null。
     */
    public String getSourceName() {
        return sourceName;
    }

    /**
     * 写入数据源名称。null 在插入时被省略，该列禁止 NULL，插入失败。
     */
    public void setSourceName(String v) {
        sourceName = v;
    }

    /**
     * 读出同步类型。列非空，默认 {@code FULL}；对象未赋值时返回 null，不代表已经读到了默认值。
     */
    public String getSyncType() {
        return syncType;
    }

    /**
     * 写入同步类型。null 在插入时被省略，数据库写入默认值 {@code FULL}。
     */
    public void setSyncType(String v) {
        syncType = v;
    }

    /**
     * 读出同步区间起点。列允许 NULL，未限定区间时返回 null。
     */
    public LocalDate getDateRangeStart() {
        return dateRangeStart;
    }

    /**
     * 写入区间起点。null 在插入时被省略，列保存为 NULL。
     */
    public void setDateRangeStart(LocalDate v) {
        dateRangeStart = v;
    }

    /**
     * 读出同步区间终点。列允许 NULL，未限定区间时返回 null。
     */
    public LocalDate getDateRangeEnd() {
        return dateRangeEnd;
    }

    /**
     * 写入区间终点。null 在插入时被省略，列保存为 NULL。
     */
    public void setDateRangeEnd(LocalDate v) {
        dateRangeEnd = v;
    }

    /**
     * 读出请求条数。列非空，默认 0；尚未赋值时返回 null。
     */
    public Integer getRequestedCount() {
        return requestedCount;
    }

    /**
     * 写入请求条数。null 在插入或按主键更新时被省略，插入走默认值 0，更新则保留原值。
     */
    public void setRequestedCount(Integer v) {
        requestedCount = v;
    }

    /**
     * 读出实际落库条数。列非空，默认 0；尚未赋值时返回 null。
     */
    public Integer getSavedCount() {
        return savedCount;
    }

    /**
     * 写入落库条数。null 在插入或按主键更新时被省略，插入走默认值 0，更新则保留原值。
     */
    public void setSavedCount(Integer v) {
        savedCount = v;
    }

    /**
     * 读出同步状态，例如 {@code RUNNING}、{@code SUCCESS}、{@code FAILED}。列非空；尚未赋值时返回 null。
     */
    public String getSyncStatus() {
        return syncStatus;
    }

    /**
     * 写入同步状态。null 在插入时被省略，该列禁止 NULL 且没有默认值，插入失败。
     */
    public void setSyncStatus(String v) {
        syncStatus = v;
    }

    /**
     * 读出错误码。列允许 NULL，成功或仍在运行时返回 null。
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * 写入错误码。按主键更新时 null 会被省略，已经写入的错误码不会被清空。
     */
    public void setErrorCode(String v) {
        errorCode = v;
    }

    /**
     * 读出错误说明。列允许 NULL，最长 500；没有错误时返回 null。
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 写入错误说明。按主键更新时 null 会被省略，原说明保留。超过 500 的文本会在数据库拒绝，本方法不截断。
     */
    public void setErrorMessage(String v) {
        errorMessage = v;
    }

    /**
     * 读出登记同步的时间。列非空；尚未赋值时返回 null。
     */
    public Instant getSyncedAt() {
        return syncedAt;
    }

    /**
     * 写入登记时间。null 在插入时被省略，该列禁止 NULL，插入失败。
     */
    public void setSyncedAt(Instant v) {
        syncedAt = v;
    }

    /**
     * 读出开始时间。列允许 NULL，尚未记录开始时间时返回 null。
     */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * 写入开始时间。null 在插入时被省略，列保存为 NULL。
     */
    public void setStartedAt(Instant v) {
        startedAt = v;
    }

    /**
     * 读出结束时间。列允许 NULL，仍在运行时返回 null。
     */
    public Instant getFinishedAt() {
        return finishedAt;
    }

    /**
     * 写入结束时间。按主键更新时传入 null 会被省略，原结束时间保留，不能用 null 表示“尚未结束”。
     */
    public void setFinishedAt(Instant v) {
        finishedAt = v;
    }
}
