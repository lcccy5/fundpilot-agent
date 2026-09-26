package com.jijing.fund.application.dto;

/**
 * 一批已启用基金同步的计数结果。
 * 单只失败不会中断整批，因此失败数可以大于零而本结果仍然正常返回。
 *
 * @param total 本批尝试同步的基金数
 * @param succeeded 单只同步成功的基金数
 * @param failed 单只同步抛出运行时异常的基金数
 */
public record BatchSyncResult(int total, int succeeded, int failed) {
}
