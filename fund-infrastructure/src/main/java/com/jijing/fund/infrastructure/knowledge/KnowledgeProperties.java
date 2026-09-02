package com.jijing.fund.infrastructure.knowledge;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="fund.knowledge")
public record KnowledgeProperties(boolean enabled,Path localRoot,String parserVersion,String chunkingVersion,
        int targetTokens,int maxTokens,int overlapTokens,int minChunkChars,int maxChunksPerDocument,
        String embeddingVersion,String indexVersion,String indexType,String elasticsearchUri,String indexName,int embeddingDimensions,int channelTopK,int rrfK,int fusedTopK,
        int rerankTopK,int maxContextTokens,int embeddingBatchSize,String elasticsearchUsername,
        String elasticsearchPassword,String elasticsearchApiKey,String elasticsearchCaCertificate,
        java.time.Duration elasticsearchConnectTimeout,java.time.Duration elasticsearchReadTimeout,String rerankerType,
        java.util.Set<String>authorizedProviderHosts,long authorizedProviderMaxBytes) {}
