package com.jijing.fund.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验证 {@link ExternalDataSourceException} 原样保留错误码、消息和原因，且不校验 null。 */
class ExternalDataSourceExceptionTest {
    /** 错误码、消息和底层原因都被原样保留。 */
    @Test
    void keepsCodeMessageAndCause() {
        var cause = new IllegalStateException("timeout");
        var exception = new ExternalDataSourceException("DATA_SOURCE_UNAVAILABLE", "provider timed out", cause);

        assertThat(exception.errorCode()).isEqualTo("DATA_SOURCE_UNAVAILABLE");
        assertThat(exception.getMessage()).isEqualTo("provider timed out");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    /** 错误码和原因为 null 时不会抛出异常，读取时同样返回 null。 */
    @Test
    void acceptsNullCodeAndCause() {
        assertThat(new ExternalDataSourceException(null, "no code").errorCode()).isNull();
        assertThat(new ExternalDataSourceException("E", "no cause", null).getCause()).isNull();
    }
}
