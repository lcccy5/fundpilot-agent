package com.jijing.fund.infrastructure.knowledge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;import org.junit.jupiter.api.Test;import org.springframework.ai.embedding.*;import static org.assertj.core.api.Assertions.assertThatThrownBy;import static org.mockito.Mockito.*;
class SpringAiDocumentEmbeddingAdapterTest {
 @Test void rejectsWrongDimensionBeforeIndexing(){EmbeddingModel model=mock(EmbeddingModel.class);when(model.embed("query")).thenReturn(new float[]{1,2});var adapter=new SpringAiDocumentEmbeddingAdapter(model,"test",3,new SimpleMeterRegistry());assertThatThrownBy(()->adapter.embedQuery("query")).isInstanceOf(IllegalStateException.class).hasMessageContaining("EMBEDDING_DIMENSION_MISMATCH");}
}
