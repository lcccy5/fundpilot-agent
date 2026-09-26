package com.jijing.fund.knowledge.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定空文档和最终失败所停住的状态不能再继续迁移。
 */
class IngestionStatusReadabilityGapTest {
    /**
     * 需要 OCR、最终失败和已被替代的版本都是终点。可重试失败不能直接变成就绪。
     */
    @Test
    void terminalStatusesDoNotResumeThePipeline() {
        assertThat(IngestionStatus.OCR_REQUIRED.canTransitionTo(IngestionStatus.PARSING)).isFalse();
        assertThat(IngestionStatus.OCR_REQUIRED.canTransitionTo(IngestionStatus.FAILED_RETRYABLE)).isFalse();
        assertThat(IngestionStatus.FAILED_FINAL.canTransitionTo(IngestionStatus.FETCHING)).isFalse();
        assertThat(IngestionStatus.FAILED_FINAL.canTransitionTo(IngestionStatus.READY)).isFalse();
        assertThat(IngestionStatus.SUPERSEDED.canTransitionTo(IngestionStatus.READY)).isFalse();
        assertThat(IngestionStatus.FAILED_RETRYABLE.canTransitionTo(IngestionStatus.FAILED_FINAL)).isTrue();
        assertThat(IngestionStatus.FAILED_RETRYABLE.canTransitionTo(IngestionStatus.READY)).isFalse();
        assertThat(IngestionStatus.PARSING.canTransitionTo(IngestionStatus.READY)).isFalse();
    }
}
