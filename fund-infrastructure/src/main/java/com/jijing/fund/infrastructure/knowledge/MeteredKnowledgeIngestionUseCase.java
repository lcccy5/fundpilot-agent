package com.jijing.fund.infrastructure.knowledge;

import com.jijing.fund.knowledge.api.*;
import io.micrometer.core.instrument.*;

public final class MeteredKnowledgeIngestionUseCase implements KnowledgeIngestionUseCase {
    private final KnowledgeIngestionUseCase delegate;private final MeterRegistry meters;
    public MeteredKnowledgeIngestionUseCase(KnowledgeIngestionUseCase delegate,MeterRegistry meters){this.delegate=delegate;this.meters=meters;}
    @Override public DocumentRegistrationResult ingest(RegisterDocumentCommand command){Timer.Sample sample=Timer.start(meters);String result="success";try{DocumentRegistrationResult response=delegate.ingest(command);result=response.status().name().toLowerCase(java.util.Locale.ROOT);meters.counter("fund.knowledge.ingestion","result",result,"document.type",command.documentType().name()).increment();return response;}catch(RuntimeException ex){result="failed";meters.counter("fund.knowledge.ingestion","result",result,"document.type",command.documentType().name()).increment();throw ex;}finally{sample.stop(meters.timer("fund.knowledge.ingestion.duration","result",result));}}
}
