package com.jijing.fund.knowledge.domain;

import java.util.Set;

/**
 * 文档版本在入库流水线中的位置。
 * <p>
 * {@code REGISTERED} 可进入抓取、已抓取或失败。{@code FETCHING} 只能到已抓取或失败。
 * {@code FETCHED} 进入解析或失败。{@code PARSING} 可以到已解析、需要 OCR，或两种失败。
 * 已解析、切块中、已切块、向量化、建索引分别只能前进一步或失败。
 * 可重试失败可以回到抓取、解析、切块、向量化、建索引，或变成最终失败。
 * {@code READY} 只能被新版本替代。{@code FAILED_FINAL}、{@code OCR_REQUIRED} 和 {@code SUPERSEDED} 不能再迁移。
 * 字符总数为 0 的文档停在 {@code OCR_REQUIRED}；切不出可检索文本则落到最终失败，而不是 OCR。
 */
public enum IngestionStatus {
    REGISTERED,
    FETCHING,
    FETCHED,
    PARSING,
    PARSED,
    CHUNKING,
    CHUNKED,
    EMBEDDING,
    INDEXING,
    READY,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    OCR_REQUIRED,
    SUPERSEDED;

    /**
     * 判断从当前状态到 {@code next} 是否在上面的迁移表里。未列出的跳跃都拒绝。
     */
    public boolean canTransitionTo(IngestionStatus next) {
        return switch (this) {
            case REGISTERED -> next == FETCHING || next == FETCHED || failure(next);
            case FETCHING -> next == FETCHED || failure(next);
            case FETCHED -> next == PARSING || failure(next);
            case PARSING -> Set.of(PARSED, OCR_REQUIRED, FAILED_RETRYABLE, FAILED_FINAL).contains(next);
            case PARSED -> next == CHUNKING || failure(next);
            case CHUNKING -> next == CHUNKED || failure(next);
            case CHUNKED -> next == EMBEDDING || failure(next);
            case EMBEDDING -> next == INDEXING || failure(next);
            case INDEXING -> next == READY || failure(next);
            case FAILED_RETRYABLE -> Set.of(FETCHING, PARSING, CHUNKING, EMBEDDING, INDEXING, FAILED_FINAL).contains(next);
            case READY -> next == SUPERSEDED;
            case FAILED_FINAL, OCR_REQUIRED, SUPERSEDED -> false;
        };
    }

    /**
     * 可重试失败和最终失败是多数中间状态都能进入的两条岔路。
     */
    private static boolean failure(IngestionStatus next) {
        return next == FAILED_RETRYABLE || next == FAILED_FINAL;
    }
}
