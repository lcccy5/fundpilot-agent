package com.jijing.fund.infrastructure.knowledge;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code fund.knowledge} 的绑定结果。Elasticsearch 连接与读超时、白名单主机和单文件上限都从这里传给适配器。
 * 记录本身不发请求，空配置不会在这里被拒绝。
 */
@ConfigurationProperties(prefix = "fund.knowledge")
public record KnowledgeProperties(boolean enabled, Path localRoot, String parserVersion, String chunkingVersion,
        int targetTokens, int maxTokens, int overlapTokens, int minChunkChars, int maxChunksPerDocument,
        String embeddingVersion, String indexVersion, String indexType, String elasticsearchUri, String indexName,
        int embeddingDimensions, int channelTopK, int rrfK, int fusedTopK, int rerankTopK, int maxContextTokens,
        int embeddingBatchSize, String elasticsearchUsername, String elasticsearchPassword, String elasticsearchApiKey,
        String elasticsearchCaCertificate, Duration elasticsearchConnectTimeout, Duration elasticsearchReadTimeout,
        String rerankerType, Set<String> authorizedProviderHosts, long authorizedProviderMaxBytes) {
    /**
     * 配置绑定生成的规范构造。不改写字段，缺省和校验留在配置元数据。
     */
    public KnowledgeProperties {
    }
}
