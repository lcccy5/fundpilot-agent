package com.jijing.fund.infrastructure.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.api.RegisterDocumentCommand;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.IngestionStatus;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcDocumentMetadataRepositoryReadabilityGapTest {
    @Test
    void matchingSourceAndHashIsADuplicateSubmit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "document_id", "doc-1",
                "version_id", "ver-1",
                "job_id", "job-1",
                "status", "READY")));
        JdbcDocumentMetadataRepository repository = new JdbcDocumentMetadataRepository(jdbc, new ObjectMapper());
        RegisterDocumentCommand command = new RegisterDocumentCommand("ext-1", "季报", FundDocumentType.QUARTERLY_REPORT,
                "publisher", "manual", URI.create("https://example.test/q.pdf"), LocalDate.of(2026, 6, 30),
                Set.of("000001"), "q.pdf", "application/pdf", new byte[] {1});

        var result = repository.register(command, "hash", "aa/hash.pdf", Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.documentId()).isEqualTo("doc-1");
        assertThat(result.status()).isEqualTo(IngestionStatus.READY);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
