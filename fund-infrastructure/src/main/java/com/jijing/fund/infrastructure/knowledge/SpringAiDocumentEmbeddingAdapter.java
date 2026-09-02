package com.jijing.fund.infrastructure.knowledge;

import com.jijing.fund.knowledge.port.DocumentEmbeddingPort;
import java.util.*;
import org.springframework.ai.embedding.EmbeddingModel;
import io.micrometer.core.instrument.*;

public final class SpringAiDocumentEmbeddingAdapter implements DocumentEmbeddingPort {
    private final EmbeddingModel model;private final String version;private final MeterRegistry meters;private final int dimensions;
    public SpringAiDocumentEmbeddingAdapter(EmbeddingModel model,String version,int dimensions,MeterRegistry meters){if(dimensions<=0)throw new IllegalArgumentException("embedding dimensions must be positive");this.model=model;this.version=version;this.dimensions=dimensions;this.meters=meters;}
    @Override public String version(){return version;}
    @Override public List<float[]> embed(List<String>texts){io.micrometer.core.instrument.Timer.Sample sample=io.micrometer.core.instrument.Timer.start(meters);try{List<float[]>result=texts.stream().map(model::embed).peek(this::validate).toList();meters.counter("fund.knowledge.embedding","result","success","version",version).increment(texts.size());return result;}catch(RuntimeException ex){meters.counter("fund.knowledge.embedding","result","failed","version",version).increment();throw ex;}finally{sample.stop(meters.timer("fund.knowledge.embedding.duration","operation","document"));}}
    @Override public float[] embedQuery(String text){io.micrometer.core.instrument.Timer.Sample sample=io.micrometer.core.instrument.Timer.start(meters);try{float[]result=model.embed(text);validate(result);return result;}finally{sample.stop(meters.timer("fund.knowledge.embedding.duration","operation","query"));}}
    private void validate(float[] vector){if(vector==null||vector.length!=dimensions)throw new IllegalStateException("EMBEDDING_DIMENSION_MISMATCH: expected "+dimensions+" but got "+(vector==null?0:vector.length));}
}
