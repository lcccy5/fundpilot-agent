package com.jijing.fund.application.dto;

/**
 * 单只基金一次同步的结果摘要。
 * 同步被拒绝或上游失败时不会返回本结果，调用方得到的是对应异常。
 *
 * @param fundCode 已接受的六位基金代码
 * @param dataSource 本次使用的外部数据源名称
 * @param fetchedCount 校验通过的净值条数
 * @param savedCount 仓储报告的写入条数
 * @param status 成功时为成功标记；失败路径不通过本结果表达
 */
public record FundSyncResult(String fundCode, String dataSource, int fetchedCount, int savedCount, String status) {
}
