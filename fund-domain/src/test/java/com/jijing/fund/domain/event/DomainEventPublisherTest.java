package com.jijing.fund.domain.event;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证 {@link DomainEventPublisher#NOOP} 对任何输入和重复调用都不抛出异常。 */
class DomainEventPublisherTest {
    /** 全部参数为 null 时也不会抛出异常。 */
    @Test
    void noopAcceptsNullArguments() {
        assertThatCode(() -> DomainEventPublisher.NOOP.append(null, null, null, null, null, null, null))
                .doesNotThrowAnyException();
    }

    /** 以相同去重键重复追加同一事件不会抛出异常。 */
    @Test
    void noopToleratesRepeatedCalls() {
        assertThatCode(() -> {
            for (int i = 0; i < 3; i++) {
                DomainEventPublisher.NOOP.append("FUND_NAV_UPDATED", "fund", "000001", null, "v1", "nav-000001", Map.of());
            }
        }).doesNotThrowAnyException();
    }
}
