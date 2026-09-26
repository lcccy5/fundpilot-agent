package com.jijing.fund.knowledge.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认状态机只允许已经声明的迁移，就绪版本不能退回解析。
 */
class IngestionStatusTest {
    /**
     * 注册可以直接到已抓取，解析中可以转到需要 OCR，就绪只能被替代。
     */
    @Test
    void onlyAllowsDeclaredTransitions() {
        assertThat(IngestionStatus.REGISTERED.canTransitionTo(IngestionStatus.FETCHED)).isTrue();
        assertThat(IngestionStatus.PARSING.canTransitionTo(IngestionStatus.OCR_REQUIRED)).isTrue();
        assertThat(IngestionStatus.READY.canTransitionTo(IngestionStatus.PARSING)).isFalse();
        assertThat(IngestionStatus.READY.canTransitionTo(IngestionStatus.SUPERSEDED)).isTrue();
    }
}
