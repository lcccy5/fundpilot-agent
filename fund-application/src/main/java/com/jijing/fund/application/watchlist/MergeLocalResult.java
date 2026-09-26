package com.jijing.fund.application.watchlist;

import java.util.List;

/**
 * 一次本地自选合并的计数。added、existing、rejected 分别是新写入、已在分组中、被拒绝的代码数。
 * rejectedCodes 保留被拒绝的原文，空代码记成空串。记录不校验三个计数与列表长度是否一致。
 */
public record MergeLocalResult(String groupId, int added, int existing, int rejected, List<String> rejectedCodes) {
}
