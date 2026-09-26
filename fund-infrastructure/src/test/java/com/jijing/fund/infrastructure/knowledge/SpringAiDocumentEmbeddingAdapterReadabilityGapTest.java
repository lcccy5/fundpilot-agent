package com.jijing.fund.infrastructure.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class SpringAiDocumentEmbeddingAdapterReadabilityGapTest {
    @Test
    void modelFailureIsCountedAndRethrown() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("query")).thenThrow(new RuntimeException("connection reset"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SpringAiDocumentEmbeddingAdapter adapter = new SpringAiDocumentEmbeddingAdapter(model, "test", 3, meters);

        assertThatThrownBy(() -> adapter.embed(List.of("query")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("connection reset");
        assertThat(meters.counter("fund.knowledge.embedding", "result", "failed", "version", "test").count())
                .isEqualTo(1);
    }

    @Test
    void nullVectorIsRejectedAsEmptyEmbedding() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("query")).thenReturn(null);
        SpringAiDocumentEmbeddingAdapter adapter = new SpringAiDocumentEmbeddingAdapter(model, "test", 3,
                new SimpleMeterRegistry());

        assertThatThrownBy(() -> adapter.embed(List.of("query")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMBEDDING_DIMENSION_MISMATCH")
                .hasMessageContaining("got 0");
    }
}
