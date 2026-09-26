package com.jijing.fund.infrastructure.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.api.KnowledgeSearchQuery;
import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.IndexedChunk;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import com.jijing.fund.knowledge.port.DocumentSearchIndex;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用 Elasticsearch REST 做 BM25 与向量 kNN，不依赖付费 RRF。
 * 连接超时缺省 2 秒，读超时缺省 10 秒。超时、拒绝连接和其他传输异常都变成
 * {@code Elasticsearch request failed}；检索外层再包一层 {@code Elasticsearch search failed}。
 * 批量响应体无法解析时抛出 {@code Invalid Elasticsearch bulk response}。
 * 空分块列表直接返回，不发请求。{@code errors:true} 视为部分失败并整批拒绝。
 * 相同 chunk id 走 index 动作，由集群覆盖，Java 侧没有重复提交分支。
 * 中断状态不会被恢复。
 */
public final class ElasticsearchHttpDocumentSearchIndex implements DocumentSearchIndex {
    private final URI baseUri;
    private final String indexName;
    private final String version;
    private final String readTarget;
    private final String writeTarget;
    private final int dimensions;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String authorization;
    private final Duration readTimeout;

    /** 开发用构造：不建读写别名，超时使用缺省值。 */
    public ElasticsearchHttpDocumentSearchIndex(String uri, String indexName, int dimensions, String version,
            ObjectMapper mapper) {
        this(uri, indexName, dimensions, version, mapper, null, null, null, Duration.ofSeconds(2),
                Duration.ofSeconds(10), false);
    }

    /** 生产构造：索引不存在则创建，并确保 read/write 别名指向它。 */
    public ElasticsearchHttpDocumentSearchIndex(String uri, String indexName, int dimensions, String version,
            ObjectMapper mapper, String username, String password, String apiKey, Duration connectTimeout,
            Duration readTimeout) {
        this(uri, indexName, dimensions, version, mapper, username, password, apiKey, connectTimeout, readTimeout,
                true);
    }

    /**
     * 构造期间就会访问集群。索引检查失败、建索引失败或别名失败都会让 Bean 创建失败。
     */
    private ElasticsearchHttpDocumentSearchIndex(String uri, String indexName, int dimensions, String version,
            ObjectMapper mapper, String username, String password, String apiKey, Duration connectTimeout,
            Duration readTimeout, boolean aliases) {
        if (indexName == null || !indexName.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid Elasticsearch index name");
        }
        this.baseUri = URI.create(uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri);
        this.indexName = indexName;
        this.dimensions = dimensions;
        this.version = version;
        this.mapper = mapper;
        Duration connect = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        this.readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(connect).build();
        if (apiKey != null && !apiKey.isBlank()) {
            this.authorization = "ApiKey " + apiKey;
        } else if (username != null && !username.isBlank()) {
            String secret = password == null ? "" : password;
            this.authorization = "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
        } else {
            this.authorization = null;
        }
        ensureIndex();
        if (aliases) {
            ensureAliases();
            this.readTarget = indexName + "_read";
            this.writeTarget = indexName + "_write";
        } else {
            this.readTarget = indexName;
            this.writeTarget = indexName;
        }
    }

    /** 对外版本由物理索引名和配置版本拼成，避免只升级配置时被当成同一索引。 */
    @Override
    public String indexVersion() {
        return indexName + ":" + version;
    }

    /**
     * 向量维度不符时在写之前拒绝。批量响应声明 errors 时不把部分成功当成整批成功。
     * 空字符串会被读成缺失节点，不进入解析失败分支；只有解析抛错才拒绝整批。
     */
    @Override
    public void index(List<IndexedChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        for (IndexedChunk value : chunks) {
            if (value.embedding() == null || value.embedding().length != dimensions) {
                throw new IllegalStateException("EMBEDDING_DIMENSION_MISMATCH before Elasticsearch write");
            }
            try {
                body.append(mapper.writeValueAsString(Map.of("index",
                        Map.of("_index", writeTarget, "_id", value.chunk().chunkId())))).append('\n');
                body.append(mapper.writeValueAsString(source(value))).append('\n');
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot serialize Elasticsearch bulk request", ex);
            }
        }
        String response = send("POST", "/_bulk?refresh=wait_for", body.toString(), "application/x-ndjson");
        try {
            JsonNode root = mapper.readTree(response);
            if (root.path("errors").asBoolean(false)) {
                throw new IllegalStateException("Elasticsearch bulk request contains partial failures");
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid Elasticsearch bulk response", ex);
        }
    }

    /** 激活某版本时删掉同一文档的其他版本；删除请求失败则激活失败。 */
    @Override
    public void activate(String documentId, String versionId) {
        try {
            Map<String, Object> bool = Map.of(
                    "filter", List.of(Map.of("term", Map.of("document_id", documentId))),
                    "must_not", List.of(Map.of("term", Map.of("version_id", versionId))));
            send("POST", "/" + writeTarget + "/_delete_by_query?refresh=true",
                    mapper.writeValueAsString(Map.of("query", Map.of("bool", bool))), "application/json");
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot activate Elasticsearch document version", ex);
        }
    }

    /** 关键词检索走 match，过滤条件与向量检索共用。 */
    @Override
    public List<RetrievedChunk> lexicalSearch(KnowledgeSearchQuery query, int topK) {
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", List.of(Map.of("match", Map.of("content", Map.of("query", query.query())))));
        List<Object> filters = filters(query);
        if (!filters.isEmpty()) {
            bool.put("filter", filters);
        }
        Map<String, Object> body = Map.of("size", topK, "query", Map.of("bool", bool), "_source", true);
        return search(body, "bm25");
    }

    /** kNN 的候选数至少 100，且不少于 topK 的四倍。 */
    @Override
    public List<RetrievedChunk> vectorSearch(KnowledgeSearchQuery query, float[] embedding, int topK) {
        Map<String, Object> knn = new LinkedHashMap<>();
        knn.put("field", "embedding");
        knn.put("query_vector", floats(embedding));
        knn.put("k", topK);
        knn.put("num_candidates", Math.max(100, topK * 4));
        List<Object> filters = filters(query);
        if (!filters.isEmpty()) {
            knn.put("filter", Map.of("bool", Map.of("filter", filters)));
        }
        return search(Map.of("size", topK, "knn", knn, "_source", true), "vector");
    }

    /** 已存在则沿用；404 才创建。其他状态说明集群不可用，不继续猜测。 */
    private void ensureIndex() {
        HttpResponse<String> head = request("HEAD", "/" + indexName, "", null);
        if (head.statusCode() == 200) {
            return;
        }
        if (head.statusCode() != 404) {
            throw new IllegalStateException("Elasticsearch index check failed: HTTP " + head.statusCode());
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String keyword : List.of("chunk_id", "document_id", "version_id", "fund_codes", "document_type",
                "content_sha256", "embedding_version", "chunking_version", "source_name")) {
            properties.put(keyword, Map.of("type", "keyword"));
        }
        properties.put("published_date", Map.of("type", "date"));
        properties.put("heading_path", Map.of("type", "text"));
        properties.put("page_start", Map.of("type", "integer"));
        properties.put("page_end", Map.of("type", "integer"));
        properties.put("chunk_order", Map.of("type", "integer"));
        properties.put("token_count", Map.of("type", "integer"));
        properties.put("document_title", Map.of("type", "text"));
        properties.put("source_uri", Map.of("type", "keyword", "ignore_above", 2048));
        properties.put("content", Map.of("type", "text"));
        properties.put("embedding", Map.of("type", "dense_vector", "dims", dimensions, "index", true,
                "similarity", "cosine"));
        try {
            send("PUT", "/" + indexName, mapper.writeValueAsString(Map.of("mappings", Map.of("properties", properties))),
                    "application/json");
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot create Elasticsearch index", ex);
        }
    }

    /** 读写别名都已存在则不动；缺哪个补哪个，写别名标记为 write index。 */
    private void ensureAliases() {
        String read = indexName + "_read";
        String write = indexName + "_write";
        boolean hasRead = request("HEAD", "/_alias/" + read, "", null).statusCode() == 200;
        boolean hasWrite = request("HEAD", "/_alias/" + write, "", null).statusCode() == 200;
        if (hasRead && hasWrite) {
            return;
        }
        List<Object> actions = new ArrayList<>();
        if (!hasRead) {
            actions.add(Map.of("add", Map.of("index", indexName, "alias", read)));
        }
        if (!hasWrite) {
            actions.add(Map.of("add", Map.of("index", indexName, "alias", write, "is_write_index", true)));
        }
        try {
            send("POST", "/_aliases", mapper.writeValueAsString(Map.of("actions", actions)), "application/json");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize Elasticsearch aliases", e);
        }
    }

    /** 把分块和向量铺成索引文档。发布日期为空时写入 JSON null。 */
    private Map<String, Object> source(IndexedChunk value) {
        DocumentChunk chunk = value.chunk();
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("chunk_id", chunk.chunkId());
        mapped.put("document_id", chunk.documentId());
        mapped.put("version_id", chunk.versionId());
        mapped.put("fund_codes", chunk.fundCodes());
        mapped.put("document_type", chunk.documentType().name());
        mapped.put("published_date", chunk.publishedDate() == null ? null : chunk.publishedDate().toString());
        mapped.put("heading_path", chunk.headingPath());
        mapped.put("page_start", chunk.pageStart());
        mapped.put("page_end", chunk.pageEnd());
        mapped.put("chunk_order", chunk.chunkOrder());
        mapped.put("token_count", chunk.tokenCount());
        mapped.put("document_title", chunk.documentTitle());
        mapped.put("content", chunk.content());
        mapped.put("content_sha256", chunk.contentSha256());
        mapped.put("chunking_version", chunk.chunkingVersion());
        mapped.put("embedding_version", value.embeddingVersion());
        mapped.put("source_name", chunk.sourceName());
        mapped.put("source_uri", chunk.sourceUri());
        mapped.put("embedding", floats(value.embedding()));
        return mapped;
    }

    /** 基金代码、文档类型和发布日期区间都是 filter，不参与打分。 */
    private List<Object> filters(KnowledgeSearchQuery query) {
        List<Object> filters = new ArrayList<>();
        if (!query.fundCodes().isEmpty()) {
            filters.add(Map.of("terms", Map.of("fund_codes", query.fundCodes())));
        }
        if (!query.documentTypes().isEmpty()) {
            filters.add(Map.of("terms", Map.of("document_type", query.documentTypes().stream().map(Enum::name).toList())));
        }
        if (query.publishedAfter() != null || query.publishedBefore() != null) {
            Map<String, Object> range = new LinkedHashMap<>();
            if (query.publishedAfter() != null) {
                range.put("gte", query.publishedAfter().toString());
            }
            if (query.publishedBefore() != null) {
                range.put("lte", query.publishedBefore().toString());
            }
            filters.add(Map.of("range", Map.of("published_date", range)));
        }
        return filters;
    }

    /** hits 缺失时得到空列表。解析或传输失败都包成检索失败，不把空结果和故障混在一起。 */
    private List<RetrievedChunk> search(Map<String, Object> body, String channel) {
        try {
            JsonNode root = mapper.readTree(send("POST", "/" + readTarget + "/_search",
                    mapper.writeValueAsString(body), "application/json"));
            List<RetrievedChunk> result = new ArrayList<>();
            int rank = 1;
            for (JsonNode hit : root.path("hits").path("hits")) {
                JsonNode source = hit.path("_source");
                DocumentChunk chunk = new DocumentChunk(text(source, "chunk_id"), text(source, "document_id"),
                        text(source, "version_id"), text(source, "content"), text(source, "heading_path"),
                        source.path("page_start").asInt(), source.path("page_end").asInt(),
                        source.path("chunk_order").asInt(), source.path("token_count").asInt(),
                        text(source, "chunking_version"), text(source, "content_sha256"),
                        stringSet(source.path("fund_codes")), FundDocumentType.valueOf(text(source, "document_type")),
                        text(source, "document_title"), date(source, "published_date"), text(source, "source_name"),
                        nullableText(source, "source_uri"));
                result.add(new RetrievedChunk(chunk, hit.path("_score").asDouble(), rank++, Set.of(channel)));
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("Elasticsearch search failed", ex);
        }
    }

    /** 非 2xx 时截取最多 300 个字符的响应体，避免把整段错误页带进异常。 */
    private String send(String method, String path, String body, String contentType) {
        HttpResponse<String> response = request(method, path, body, contentType);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String responseBody = response.body();
            throw new IllegalStateException("Elasticsearch HTTP " + response.statusCode() + ": "
                    + responseBody.substring(0, Math.min(300, responseBody.length())));
        }
        return response.body();
    }

    /** 传输层失败统一抛出，不在这里区分超时和拒绝连接。 */
    private HttpResponse<String> request(String method, String path, String body, String contentType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(readTimeout);
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            if (authorization != null) {
                builder.header("Authorization", authorization);
            }
            HttpRequest.BodyPublisher publisher = body == null || body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
            builder.method(method, publisher);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new IllegalStateException("Elasticsearch request failed", ex);
        }
    }

    /** 向量写成 JSON 数组。null 向量由调用方提前拒绝。 */
    private List<Float> floats(float[] values) {
        List<Float> converted = new ArrayList<>(values.length);
        for (float value : values) {
            converted.add(value);
        }
        return converted;
    }

    /** 基金代码数组缺失时得到空集合。 */
    private Set<String> stringSet(JsonNode node) {
        Set<String> values = new HashSet<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    /** 缺失字段变成空字符串，供必填文本使用。 */
    private String text(JsonNode node, String field) {
        return node.path(field).asText();
    }

    /** JSON null 或缺失字段返回 null，用于可选的来源地址和日期。 */
    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    /** 无可解析日期时返回 null。 */
    private LocalDate date(JsonNode node, String field) {
        String value = nullableText(node, field);
        return value == null ? null : LocalDate.parse(value);
    }
}
