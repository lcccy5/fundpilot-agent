package com.jijing.fund.infrastructure.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.api.KnowledgeAdministrationUseCase;
import com.jijing.fund.knowledge.api.KnowledgeIndexGovernanceUseCase;
import com.jijing.fund.knowledge.api.KnowledgeIngestionUseCase;
import com.jijing.fund.knowledge.api.KnowledgeSearchUseCase;
import com.jijing.fund.knowledge.port.AuthorizedDocumentProvider;
import com.jijing.fund.knowledge.port.DocumentEmbeddingPort;
import com.jijing.fund.knowledge.port.DocumentMetadataRepository;
import com.jijing.fund.knowledge.port.DocumentParser;
import com.jijing.fund.knowledge.port.DocumentReranker;
import com.jijing.fund.knowledge.port.DocumentSearchIndex;
import com.jijing.fund.knowledge.port.IndexGovernanceGateway;
import com.jijing.fund.knowledge.port.IndexRebuildRepository;
import com.jijing.fund.knowledge.port.KnowledgeCheckpointStore;
import com.jijing.fund.knowledge.port.KnowledgeJobRepository;
import com.jijing.fund.knowledge.port.RawDocumentStore;
import com.jijing.fund.knowledge.service.ChineseDocumentChunker;
import com.jijing.fund.knowledge.service.ChineseOverlapDocumentReranker;
import com.jijing.fund.knowledge.service.HybridKnowledgeSearchService;
import com.jijing.fund.knowledge.service.KnowledgeAdministrationService;
import com.jijing.fund.knowledge.service.KnowledgeIndexGovernanceService;
import com.jijing.fund.knowledge.service.KnowledgeIngestionProcessor;
import com.jijing.fund.knowledge.service.KnowledgeIngestionService;
import com.jijing.fund.knowledge.service.NoOpDocumentReranker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 知识库基础设施装配。只有 {@code fund.knowledge.enabled=true} 时生效。
 * 超时、空响应和连接失败的具体行为在各个适配器里，本类只把配置交进去。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeProperties.class)
@ConditionalOnProperty(prefix = "fund.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeInfrastructureConfiguration {
    /** 文档元数据走 JDBC，JSON 字段使用应用的 ObjectMapper。 */
    @Bean
    DocumentMetadataRepository documentMetadataRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcDocumentMetadataRepository(jdbc, mapper);
    }

    /** 摄取任务的领取、心跳和重试状态。 */
    @Bean
    KnowledgeJobRepository knowledgeJobRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcKnowledgeJobRepository(jdbc, mapper);
    }

    /** 原文落在配置的本地根目录。 */
    @Bean
    RawDocumentStore rawDocumentStore(KnowledgeProperties properties) {
        return new LocalRawDocumentStore(properties.localRoot());
    }

    /** 检查点放在原文根目录下的 artifacts。 */
    @Bean
    KnowledgeCheckpointStore knowledgeCheckpointStore(KnowledgeProperties properties, ObjectMapper mapper) {
        return new LocalKnowledgeCheckpointStore(properties.localRoot().resolve("artifacts"), mapper);
    }

    /** PDF、HTML 和纯文本解析器，无外部依赖。 */
    @Bean
    DocumentParser documentParser() {
        return new PdfHtmlTextDocumentParser();
    }

    /** 中文切块参数全部来自配置，本类不设第二套缺省。 */
    @Bean
    ChineseDocumentChunker chineseDocumentChunker(KnowledgeProperties properties) {
        return new ChineseDocumentChunker(properties.targetTokens(), properties.maxTokens(), properties.overlapTokens(),
                properties.minChunkChars(), properties.maxChunksPerDocument());
    }

    /** 嵌入维度和版本从配置传入，失败计数进 Micrometer。 */
    @Bean
    DocumentEmbeddingPort documentEmbeddingPort(EmbeddingModel model, KnowledgeProperties properties,
            MeterRegistry meters) {
        return new SpringAiDocumentEmbeddingAdapter(model, properties.embeddingVersion(),
                properties.embeddingDimensions(), meters);
    }

    /** 未显式选择 elasticsearch 时使用内存索引。 */
    @Bean
    @ConditionalOnProperty(prefix = "fund.knowledge", name = "index-type", havingValue = "local", matchIfMissing = true)
    DocumentSearchIndex localDocumentSearchIndex(KnowledgeProperties properties) {
        return new InMemoryDocumentSearchIndex(properties.indexVersion());
    }

    /** 把连接超时和读超时原样交给 HTTP 索引，不在这里改缺省。 */
    @Bean
    @ConditionalOnProperty(prefix = "fund.knowledge", name = "index-type", havingValue = "elasticsearch")
    DocumentSearchIndex elasticsearchDocumentSearchIndex(KnowledgeProperties properties, ObjectMapper mapper) {
        return new ElasticsearchHttpDocumentSearchIndex(properties.elasticsearchUri(), properties.indexName(),
                properties.embeddingDimensions(), properties.indexVersion(), mapper, properties.elasticsearchUsername(),
                properties.elasticsearchPassword(), properties.elasticsearchApiKey(),
                properties.elasticsearchConnectTimeout(), properties.elasticsearchReadTimeout());
    }

    /** {@code chinese-overlap} 以外的取值都退回空重排。 */
    @Bean
    DocumentReranker documentReranker(KnowledgeProperties properties) {
        return "chinese-overlap".equalsIgnoreCase(properties.rerankerType())
                ? new ChineseOverlapDocumentReranker() : new NoOpDocumentReranker();
    }

    /** 摄取入口外包一层指标，业务仍由领域服务完成。 */
    @Bean
    KnowledgeIngestionUseCase knowledgeIngestionUseCase(DocumentMetadataRepository repository, RawDocumentStore raw,
            Clock clock, MeterRegistry meters) {
        return new MeteredKnowledgeIngestionUseCase(new KnowledgeIngestionService(repository, raw, clock), meters);
    }

    /** 后台处理器把解析、切块、嵌入和索引串起来。 */
    @Bean
    KnowledgeIngestionProcessor knowledgeIngestionProcessor(DocumentMetadataRepository repository,
            KnowledgeJobRepository jobs, KnowledgeCheckpointStore checkpoints, RawDocumentStore raw,
            DocumentParser parser, ChineseDocumentChunker chunker, DocumentEmbeddingPort embeddings,
            DocumentSearchIndex index, Clock clock, KnowledgeProperties properties) {
        return new KnowledgeIngestionProcessor(repository, jobs, checkpoints, raw, parser, chunker, embeddings, index,
                clock, properties.parserVersion(), properties.chunkingVersion(), properties.embeddingBatchSize());
    }

    /** 任务查询和人工重试。 */
    @Bean
    KnowledgeAdministrationUseCase knowledgeAdministrationUseCase(KnowledgeJobRepository jobs, Clock clock) {
        return new KnowledgeAdministrationService(jobs, clock);
    }

    /** 只有打开授权拉取时才创建白名单 HTTP 客户端。 */
    @Bean
    @ConditionalOnProperty(prefix = "fund.knowledge", name = "authorized-provider-enabled", havingValue = "true")
    AuthorizedDocumentProvider authorizedDocumentProvider(KnowledgeProperties properties) {
        return new AllowlistedHttpDocumentProvider(properties.authorizedProviderHosts(),
                properties.authorizedProviderMaxBytes());
    }

    /** 重建记录只在 Elasticsearch 模式下需要。 */
    @Bean
    @ConditionalOnProperty(prefix = "fund.knowledge", name = "index-type", havingValue = "elasticsearch")
    IndexRebuildRepository indexRebuildRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcIndexRebuildRepository(jdbc, mapper);
    }

    /** 治理网关使用与检索相同的超时和凭据。 */
    @Bean
    @ConditionalOnProperty(prefix = "fund.knowledge", name = "index-type", havingValue = "elasticsearch")
    IndexGovernanceGateway indexGovernanceGateway(KnowledgeProperties properties, ObjectMapper mapper) {
        return new ElasticsearchIndexGovernanceGateway(properties.elasticsearchUri(), properties.indexName(), mapper,
                properties.elasticsearchUsername(), properties.elasticsearchPassword(),
                properties.elasticsearchApiKey(), properties.elasticsearchConnectTimeout(),
                properties.elasticsearchReadTimeout());
    }

    /** 重建、激活和回滚的应用服务。 */
    @Bean
    @ConditionalOnProperty(prefix = "fund.knowledge", name = "index-type", havingValue = "elasticsearch")
    KnowledgeIndexGovernanceUseCase knowledgeIndexGovernanceUseCase(IndexGovernanceGateway gateway,
            IndexRebuildRepository repository, Clock clock, KnowledgeProperties properties) {
        return new KnowledgeIndexGovernanceService(gateway, repository, clock, properties.embeddingVersion(),
                properties.chunkingVersion());
    }

    /** 混合检索外包指标。通道宽度和 RRF 参数来自配置。 */
    @Bean
    KnowledgeSearchUseCase knowledgeSearchUseCase(DocumentEmbeddingPort embeddings, DocumentSearchIndex index,
            DocumentReranker reranker, KnowledgeProperties properties, MeterRegistry meters) {
        HybridKnowledgeSearchService search = new HybridKnowledgeSearchService(embeddings, index, reranker,
                properties.channelTopK(), properties.rrfK(), properties.fusedTopK(), properties.rerankTopK(),
                properties.maxContextTokens());
        return new MeteredKnowledgeSearchUseCase(search, meters);
    }
}
