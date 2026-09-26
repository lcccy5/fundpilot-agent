package com.jijing.fund.application.dto;

import java.util.List;

/**
 * 一只基金在请求区间内的净值历史。
 * 区间非法或基金不存在时不会返回本结果；没有样本时列表为空，来源标记为数据库。
 *
 * @param fundCode 已接受的六位基金代码
 * @param dataSource 样本来源；空序列固定标记为数据库
 * @param items 按仓储返回顺序排列的净值点
 */
public record FundNavHistoryResult(String fundCode, String dataSource, List<NavPointResult> items) {
}
