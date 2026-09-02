package com.jijing.fund.knowledge.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IngestionStatusTest {
    @Test void onlyAllowsDeclaredTransitions(){
        assertThat(IngestionStatus.REGISTERED.canTransitionTo(IngestionStatus.FETCHED)).isTrue();
        assertThat(IngestionStatus.PARSING.canTransitionTo(IngestionStatus.OCR_REQUIRED)).isTrue();
        assertThat(IngestionStatus.READY.canTransitionTo(IngestionStatus.PARSING)).isFalse();
        assertThat(IngestionStatus.READY.canTransitionTo(IngestionStatus.SUPERSEDED)).isTrue();
    }
}
