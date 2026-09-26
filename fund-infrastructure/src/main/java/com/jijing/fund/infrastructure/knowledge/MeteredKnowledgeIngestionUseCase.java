package com.jijing.fund.infrastructure.knowledge;

import com.jijing.fund.knowledge.api.DocumentRegistrationResult;
import com.jijing.fund.knowledge.api.KnowledgeIngestionUseCase;
import com.jijing.fund.knowledge.api.RegisterDocumentCommand;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;

/**
 * 给摄取用例加上计数和耗时。不改变委托对象的超时、空结果或重复提交语义。
 * 成功时结果标签是状态名的小写；异常记为 failed 后原样抛出。耗时在 finally 中记录，失败也会留下。
 */
public final class MeteredKnowledgeIngestionUseCase implements KnowledgeIngestionUseCase {
    private final KnowledgeIngestionUseCase delegate;
    private final MeterRegistry meters;

    /** 委托对象负责业务，这里只持有指标注册表。 */
    public MeteredKnowledgeIngestionUseCase(KnowledgeIngestionUseCase delegate, MeterRegistry meters) {
        this.delegate = delegate;
        this.meters = meters;
    }

    /** 计数维度包含文档类型，便于区分哪类原文更容易失败。 */
    @Override
    public DocumentRegistrationResult ingest(RegisterDocumentCommand command) {
        Timer.Sample sample = Timer.start(meters);
        String result = "success";
        try {
            DocumentRegistrationResult response = delegate.ingest(command);
            result = response.status().name().toLowerCase(Locale.ROOT);
            meters.counter("fund.knowledge.ingestion", "result", result, "document.type",
                    command.documentType().name()).increment();
            return response;
        } catch (RuntimeException ex) {
            result = "failed";
            meters.counter("fund.knowledge.ingestion", "result", result, "document.type",
                    command.documentType().name()).increment();
            throw ex;
        } finally {
            sample.stop(meters.timer("fund.knowledge.ingestion.duration", "result", result));
        }
    }
}
