package com.jijing.fund.infrastructure.knowledge;

import com.jijing.fund.knowledge.api.KnowledgeSearchQuery;
import com.jijing.fund.knowledge.api.KnowledgeSearchResult;
import com.jijing.fund.knowledge.api.KnowledgeSearchUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 给检索用例加上计数、结果条数和耗时。空结果仍算成功，条数记 0。
 * 委托对象抛出的运行时异常记为 failed 后原样抛出。本类自己没有超时或连接处理。
 */
public final class MeteredKnowledgeSearchUseCase implements KnowledgeSearchUseCase {
    private final KnowledgeSearchUseCase delegate;
    private final MeterRegistry meters;

    /** 指标与检索实现分开，避免检索服务依赖 Micrometer。 */
    public MeteredKnowledgeSearchUseCase(KnowledgeSearchUseCase delegate, MeterRegistry meters) {
        this.delegate = delegate;
        this.meters = meters;
    }

    /** 成功计数发生在返回前；耗时标签使用 finally 里已经更新的结果。 */
    @Override
    public KnowledgeSearchResult search(KnowledgeSearchQuery query) {
        Timer.Sample sample = Timer.start(meters);
        String result = "success";
        try {
            KnowledgeSearchResult response = delegate.search(query);
            meters.summary("fund.knowledge.search.results").record(response.chunks().size());
            meters.counter("fund.knowledge.search", "result", result).increment();
            return response;
        } catch (RuntimeException ex) {
            result = "failed";
            meters.counter("fund.knowledge.search", "result", result).increment();
            throw ex;
        } finally {
            sample.stop(meters.timer("fund.knowledge.search.duration", "result", result));
        }
    }
}
