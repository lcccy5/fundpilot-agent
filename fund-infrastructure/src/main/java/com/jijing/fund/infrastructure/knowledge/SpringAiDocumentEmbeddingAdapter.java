package com.jijing.fund.infrastructure.knowledge;

import com.jijing.fund.knowledge.port.DocumentEmbeddingPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * 把 Spring AI 的嵌入模型接到文档端口，并校验维度。
 * 本类不设置 HTTP 超时，模型调用挂起多久由模型客户端决定。
 * {@link #embed} 遇到运行时异常（含模型侧连接失败）会计失败次数后原样抛出。
 * {@link #embedQuery} 不捕获异常，只保证计时器停止。
 * 返回 null 或长度不符视为空向量或坏向量，抛出 {@code EMBEDDING_DIMENSION_MISMATCH}。
 * 空文本列表返回空列表并记成功。没有重复提交去重，相同文本会再次调用模型。
 */
public final class SpringAiDocumentEmbeddingAdapter implements DocumentEmbeddingPort {
    private final EmbeddingModel model;
    private final String version;
    private final MeterRegistry meters;
    private final int dimensions;

    /** 维度必须为正，否则后续索引写入无法判断向量是否完整。 */
    public SpringAiDocumentEmbeddingAdapter(EmbeddingModel model, String version, int dimensions,
            MeterRegistry meters) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("embedding dimensions must be positive");
        }
        this.model = model;
        this.version = version;
        this.dimensions = dimensions;
        this.meters = meters;
    }

    /** 配置中的嵌入版本，写入分块元数据，不传给模型。 */
    @Override
    public String version() {
        return version;
    }

    /** 逐条嵌入。任一向量维度不对或模型抛错时，已完成的条目不会部分返回。 */
    @Override
    public List<float[]> embed(List<String> texts) {
        Timer.Sample sample = Timer.start(meters);
        try {
            List<float[]> result = texts.stream().map(model::embed).peek(this::validate).toList();
            meters.counter("fund.knowledge.embedding", "result", "success", "version", version).increment(texts.size());
            return result;
        } catch (RuntimeException ex) {
            meters.counter("fund.knowledge.embedding", "result", "failed", "version", version).increment();
            throw ex;
        } finally {
            sample.stop(meters.timer("fund.knowledge.embedding.duration", "operation", "document"));
        }
    }

    /** 查询向量失败时不增加失败计数，只停止计时。 */
    @Override
    public float[] embedQuery(String text) {
        Timer.Sample sample = Timer.start(meters);
        try {
            float[] result = model.embed(text);
            validate(result);
            return result;
        } finally {
            sample.stop(meters.timer("fund.knowledge.embedding.duration", "operation", "query"));
        }
    }

    /** null 按长度 0 报告，便于和维度不符区分开。 */
    private void validate(float[] vector) {
        if (vector == null || vector.length != dimensions) {
            int actual = vector == null ? 0 : vector.length;
            throw new IllegalStateException(
                    "EMBEDDING_DIMENSION_MISMATCH: expected " + dimensions + " but got " + actual);
        }
    }
}
