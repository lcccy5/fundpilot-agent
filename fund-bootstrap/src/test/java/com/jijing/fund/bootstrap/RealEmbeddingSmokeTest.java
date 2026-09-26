package com.jijing.fund.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 显式打开的真实向量冒烟测试，核对向量维度与配置一致。默认构建不执行。
 */
@SpringBootTest(properties={"spring.ai.model.embedding=${AI_EMBEDDING_PROVIDER:openai}","fund.knowledge.enabled=false"})
@EnabledIfEnvironmentVariable(named="RUN_REAL_EMBEDDING_TEST",matches="true")
class RealEmbeddingSmokeTest {
    @Autowired EmbeddingModel model;
    @Value("${fund.knowledge.embedding-dimensions}") int configuredDimensions;

    /**
     * 对一句基金文本取向量，长度必须等于配置的维度。
     */
    @Test void realEmbeddingHasConfiguredDimension(){float[]vector=model.embed("基金风险与收益测试");assertThat(vector).hasSize(configuredDimensions);}
}
