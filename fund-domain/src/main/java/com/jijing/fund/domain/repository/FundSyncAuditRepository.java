package com.jijing.fund.domain.repository;

import com.jijing.fund.domain.model.FundCode;
import java.time.*;

/**
 * 基金数据同步的审计记录端口：同步开始时登记一条“运行中”记录，结束时标记成功或失败。
 * 审计写入应独立于同步业务事务，以便同步失败回滚时审计记录仍被保留。
 */
public interface FundSyncAuditRepository {
    /** 登记一次同步开始，记录基金、数据源和请求区间，返回用于后续标记结果的审计记录标识。 */
    long start(FundCode code, String source, LocalDate startDate, LocalDate endDate);

    /** 把审计记录标记为成功并记录请求条数、实际保存条数和结束时间；记录不存在时静默忽略。 */
    void success(long id, int requestedCount, int savedCount, Instant finishedAt);

    /** 把审计记录标记为失败并记录错误码、可安全展示的错误说明和结束时间；记录不存在时静默忽略。 */
    void failure(long id, String errorCode, String safeMessage, Instant finishedAt);
}
