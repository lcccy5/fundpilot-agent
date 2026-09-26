package com.jijing.fund.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code fund_metric_snapshot} 的一行指标快照。身份是
 * {@code (fund_code, period_code, nav_basis, data_revision, algorithm_version)}，冲突时覆盖可空指标列。
 * 表上另有自增主键和 {@code created_at}、{@code updated_at}，本类不保存它们，因此无法按代理主键判断缺行。
 * 没有对应查询方法：写库前不会先读旧行。收益类字段为 null 表示该指标不可用，绑定后保存为 SQL NULL。
 * 非空列若保持 null，插入或重复键更新会在数据库失败。
 */
public class FundMetricSnapshotEntity {
    /** 基金代码。列非空，且是唯一键的一部分；null 会绑定成 SQL NULL 并被拒绝。 */
    public String fundCode;
    /** 区间代码。列非空，且是唯一键的一部分；null 会绑定成 SQL NULL 并被拒绝。 */
    public String periodCode;
    /** 净值口径的枚举名。列非空，且是唯一键的一部分；null 会绑定成 SQL NULL 并被拒绝。 */
    public String navBasis;
    /** 算法版本。列非空，且是唯一键的一部分；null 会绑定成 SQL NULL 并被拒绝。同一基金可以并存多个版本。 */
    public String algorithmVersion;
    /** 实际参与计算的起始日。列非空；null 会绑定成 SQL NULL 并被拒绝。 */
    public LocalDate actualStartDate;
    /** 实际参与计算的结束日。列非空；null 会绑定成 SQL NULL 并被拒绝。重复键时会被新值覆盖。 */
    public LocalDate actualEndDate;
    /** 观测个数。列非空。未赋值时为 null，自定义插入会把它绑定成 SQL NULL 并被拒绝；只有显式写入的数字才会落库。 */
    public Integer observationCount;
    /** 覆盖率。列允许 NULL；覆盖率未知时保存 SQL NULL，重复键更新会用新的 null 覆盖旧值。 */
    public BigDecimal coverageRate;
    /** 累计收益。列允许 NULL；指标不可用时保存 SQL NULL。 */
    public BigDecimal cumulativeReturn;
    /** 年化收益。列允许 NULL；指标不可用时保存 SQL NULL。 */
    public BigDecimal annualizedReturn;
    /** 年化波动。列允许 NULL；指标不可用时保存 SQL NULL。 */
    public BigDecimal annualizedVolatility;
    /** 最大回撤。列允许 NULL；指标不可用时保存 SQL NULL。回撤区间本身没有对应列。 */
    public BigDecimal maxDrawdown;
    /** 夏普比率。列允许 NULL；指标不可用时保存 SQL NULL。 */
    public BigDecimal sharpeRatio;
    /** 上涨日占比。列允许 NULL；指标不可用时保存 SQL NULL。 */
    public BigDecimal positiveDayRatio;
    /** 年化无风险利率。列非空；null 会绑定成 SQL NULL，插入和重复键更新都会失败。 */
    public BigDecimal riskFreeRate;
    /** 计算所依据的基金数据修订号。列非空，且是唯一键的一部分；null 会绑定成 SQL NULL 并被拒绝。修订号变化会新插一行。 */
    public Long dataRevision;
    /** 计算完成时间。列非空；null 会绑定成 SQL NULL 并被拒绝。重复键时更新为新的计算时间。 */
    public Instant calculatedAt;
}
