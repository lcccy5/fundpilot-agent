package com.jijing.fund.infrastructure.knowledge;

import com.jijing.fund.knowledge.api.*;
import io.micrometer.core.instrument.*;

public final class MeteredKnowledgeSearchUseCase implements KnowledgeSearchUseCase {
    private final KnowledgeSearchUseCase delegate;private final MeterRegistry meters;
    public MeteredKnowledgeSearchUseCase(KnowledgeSearchUseCase delegate,MeterRegistry meters){this.delegate=delegate;this.meters=meters;}
    @Override public KnowledgeSearchResult search(KnowledgeSearchQuery query){Timer.Sample sample=Timer.start(meters);String result="success";try{KnowledgeSearchResult response=delegate.search(query);meters.summary("fund.knowledge.search.results").record(response.chunks().size());meters.counter("fund.knowledge.search","result",result).increment();return response;}catch(RuntimeException ex){result="failed";meters.counter("fund.knowledge.search","result",result).increment();throw ex;}finally{sample.stop(meters.timer("fund.knowledge.search.duration","result",result));}}
}
