package com.jijing.fund.application.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 面向调用方的基金档案，附带采集新鲜度。
 * 基金不存在或代码非法时不会返回本结果。新鲜度为新鲜或陈旧，由采集时刻与当前时钟的间隔决定。
 *
 * @param fundCode 六位基金代码
 * @param name 基金名称
 * @param fundType 基金类型，上游未提供时可以为空
 * @param managementCompany 管理人，上游未提供时可以为空
 * @param fundManager 基金经理，上游未提供时可以为空
 * @param establishedDate 成立日，上游未提供时可以为空
 * @param dataSource 档案来源名称
 * @param sourceUpdatedAt 上游更新时刻，可以为空
 * @param collectedAt 本地采集时刻
 * @param freshness 采集时刻四十八小时内为新鲜，否则为陈旧
 */
public record FundProfileResult(String fundCode, String name, String fundType, String managementCompany,
        String fundManager, LocalDate establishedDate, String dataSource, Instant sourceUpdatedAt,
        Instant collectedAt, String freshness) {
}
