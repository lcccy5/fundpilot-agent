package com.jijing.fund.infrastructure.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalRawDocumentStoreReadabilityGapTest {
    @Test
    void storingTheSameHashTwiceKeepsTheFirstBytes(@TempDir java.nio.file.Path root) throws Exception {
        LocalRawDocumentStore store = new LocalRawDocumentStore(root);
        String hash = "ab0123456789abcdef";

        String first = store.store(hash, "report.pdf", new byte[] {1, 2, 3});
        String second = store.store(hash, "report.pdf", new byte[] {9, 9, 9});

        assertThat(second).isEqualTo(first);
        assertThat(store.read(first)).containsExactly(1, 2, 3);
        assertThat(Files.size(root.resolve(first))).isEqualTo(3);
    }
}
