package com.jijing.fund.infrastructure.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.port.*;
import com.jijing.fund.knowledge.service.*;
import java.time.Clock;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(KnowledgeProperties.class)
@ConditionalOnProperty(prefix="fund.knowledge",name="enabled",havingValue="true")
public class KnowledgeInfrastructureConfiguration {
    @Bean DocumentMetadataRepository documentMetadataRepository(JdbcTemplate jdbc,ObjectMapper mapper){return new JdbcDocumentMetadataRepository(jdbc,mapper);}
    @Bean KnowledgeJobRepository knowledgeJobRepository(JdbcTemplate jdbc,ObjectMapper mapper){return new JdbcKnowledgeJobRepository(jdbc,mapper);}
    @Bean RawDocumentStore rawDocumentStore(KnowledgeProperties p){return new LocalRawDocumentStore(p.localRoot());}
    @Bean KnowledgeCheckpointStore knowledgeCheckpointStore(KnowledgeProperties p,ObjectMapper mapper){return new LocalKnowledgeCheckpointStore(p.localRoot().resolve("artifacts"),mapper);}
    @Bean DocumentParser documentParser(){return new PdfHtmlTextDocumentParser();}
    @Bean ChineseDocumentChunker chineseDocumentChunker(KnowledgeProperties p){return new ChineseDocumentChunker(p.targetTokens(),p.maxTokens(),p.overlapTokens(),p.minChunkChars(),p.maxChunksPerDocument());}
    @Bean DocumentEmbeddingPort documentEmbeddingPort(EmbeddingModel model,KnowledgeProperties p,MeterRegistry meters){return new SpringAiDocumentEmbeddingAdapter(model,p.embeddingVersion(),p.embeddingDimensions(),meters);}
    @Bean @ConditionalOnProperty(prefix="fund.knowledge",name="index-type",havingValue="local",matchIfMissing=true)
    DocumentSearchIndex localDocumentSearchIndex(KnowledgeProperties p){return new InMemoryDocumentSearchIndex(p.indexVersion());}
    @Bean @ConditionalOnProperty(prefix="fund.knowledge",name="index-type",havingValue="elasticsearch")
    DocumentSearchIndex elasticsearchDocumentSearchIndex(KnowledgeProperties p,ObjectMapper mapper){return new ElasticsearchHttpDocumentSearchIndex(p.elasticsearchUri(),p.indexName(),p.embeddingDimensions(),p.indexVersion(),mapper,p.elasticsearchUsername(),p.elasticsearchPassword(),p.elasticsearchApiKey(),p.elasticsearchConnectTimeout(),p.elasticsearchReadTimeout());}
    @Bean DocumentReranker documentReranker(KnowledgeProperties p){return "chinese-overlap".equalsIgnoreCase(p.rerankerType())?new ChineseOverlapDocumentReranker():new NoOpDocumentReranker();}
    @Bean KnowledgeIngestionUseCase knowledgeIngestionUseCase(DocumentMetadataRepository repository,RawDocumentStore raw,Clock clock,MeterRegistry meters){return new MeteredKnowledgeIngestionUseCase(new KnowledgeIngestionService(repository,raw,clock),meters);}
    @Bean KnowledgeIngestionProcessor knowledgeIngestionProcessor(DocumentMetadataRepository repository,KnowledgeJobRepository jobs,KnowledgeCheckpointStore checkpoints,RawDocumentStore raw,DocumentParser parser,ChineseDocumentChunker chunker,DocumentEmbeddingPort embeddings,DocumentSearchIndex index,Clock clock,KnowledgeProperties p){return new KnowledgeIngestionProcessor(repository,jobs,checkpoints,raw,parser,chunker,embeddings,index,clock,p.parserVersion(),p.chunkingVersion(),p.embeddingBatchSize());}
    @Bean KnowledgeAdministrationUseCase knowledgeAdministrationUseCase(KnowledgeJobRepository jobs,Clock clock){return new KnowledgeAdministrationService(jobs,clock);}
    @Bean @ConditionalOnProperty(prefix="fund.knowledge",name="authorized-provider-enabled",havingValue="true")
    AuthorizedDocumentProvider authorizedDocumentProvider(KnowledgeProperties p){return new AllowlistedHttpDocumentProvider(p.authorizedProviderHosts(),p.authorizedProviderMaxBytes());}
    @Bean @ConditionalOnProperty(prefix="fund.knowledge",name="index-type",havingValue="elasticsearch")
    IndexRebuildRepository indexRebuildRepository(JdbcTemplate jdbc,ObjectMapper mapper){return new JdbcIndexRebuildRepository(jdbc,mapper);}
    @Bean @ConditionalOnProperty(prefix="fund.knowledge",name="index-type",havingValue="elasticsearch")
    IndexGovernanceGateway indexGovernanceGateway(KnowledgeProperties p,ObjectMapper mapper){return new ElasticsearchIndexGovernanceGateway(p.elasticsearchUri(),p.indexName(),mapper,p.elasticsearchUsername(),p.elasticsearchPassword(),p.elasticsearchApiKey(),p.elasticsearchConnectTimeout(),p.elasticsearchReadTimeout());}
    @Bean @ConditionalOnProperty(prefix="fund.knowledge",name="index-type",havingValue="elasticsearch")
    KnowledgeIndexGovernanceUseCase knowledgeIndexGovernanceUseCase(IndexGovernanceGateway gateway,IndexRebuildRepository repository,Clock clock,KnowledgeProperties p){return new KnowledgeIndexGovernanceService(gateway,repository,clock,p.embeddingVersion(),p.chunkingVersion());}
    @Bean KnowledgeSearchUseCase knowledgeSearchUseCase(DocumentEmbeddingPort embeddings,DocumentSearchIndex index,DocumentReranker reranker,KnowledgeProperties p,MeterRegistry meters){return new MeteredKnowledgeSearchUseCase(new HybridKnowledgeSearchService(embeddings,index,reranker,p.channelTopK(),p.rrfK(),p.fusedTopK(),p.rerankTopK(),p.maxContextTokens()),meters);}
}
