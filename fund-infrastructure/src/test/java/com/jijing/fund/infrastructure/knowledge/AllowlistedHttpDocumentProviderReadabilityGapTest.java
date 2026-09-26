package com.jijing.fund.infrastructure.knowledge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AllowlistedHttpDocumentProviderReadabilityGapTest {
    @Test
    @Timeout(15)
    void unreachableAllowlistedHostFailsTheFetch() {
        AllowlistedHttpDocumentProvider provider = new AllowlistedHttpDocumentProvider(Set.of("203.0.113.1"), 1024);

        assertThatThrownBy(() -> provider.fetch(URI.create("https://203.0.113.1/report.txt")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document fetch failed");
    }
}
