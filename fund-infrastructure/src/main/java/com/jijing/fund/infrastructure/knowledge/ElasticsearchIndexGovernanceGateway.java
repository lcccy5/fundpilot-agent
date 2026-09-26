package com.jijing.fund.infrastructure.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.port.IndexGovernanceGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重建 Elasticsearch 索引并切换读写别名。
 * 连接超时缺省 2 秒，读超时缺省 30 秒。超时和连接失败都是 {@code Elasticsearch governance request failed}。
 * 别名 200 但正文无法解析时抛出 {@code Invalid alias response}。
 * reindex 使用 {@code op_type=create} 且 {@code conflicts=proceed}，版本冲突只写入校验报告，不单独抛出。
 * Java 侧没有重复提交去重；重复 rebuild 会再建一个带时间戳的目标索引。
 */
public final class ElasticsearchIndexGovernanceGateway implements IndexGovernanceGateway {
    private final URI base;
    private final String baseIndex;
    private final String readAlias;
    private final String writeAlias;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Duration timeout;
    private final String authorization;

    /** 读超时为空时用 30 秒，避免 reindex 等待被过短的缺省值打断；连接超时为空时用 2 秒。 */
    public ElasticsearchIndexGovernanceGateway(String uri, String baseIndex, ObjectMapper mapper, String username,
            String password, String apiKey, Duration connect, Duration read) {
        this.base = URI.create(uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri);
        this.baseIndex = baseIndex;
        this.readAlias = baseIndex + "_read";
        this.writeAlias = baseIndex + "_write";
        this.mapper = mapper;
        this.timeout = read == null ? Duration.ofSeconds(30) : read;
        Duration connectTimeout = connect == null ? Duration.ofSeconds(2) : connect;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        if (apiKey != null && !apiKey.isBlank()) {
            authorization = "ApiKey " + apiKey;
        } else if (username != null && !username.isBlank()) {
            String secret = password == null ? "" : password;
            authorization = "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
        } else {
            authorization = null;
        }
    }

    /**
     * 从当前读别名或基索引复制 mapping，再全量 reindex。目标名带 UTC 时间和重建号前 8 位。
     */
    @Override
    public PreparedIndex rebuild(String rebuildId) {
        String previous = currentIndex();
        String stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        String target = baseIndex + "_" + stamp + "_" + rebuildId.substring(0, 8);
        JsonNode mappings = read("GET", "/" + previous + "/_mapping", null).path(previous).path("mappings");
        send("PUT", "/" + target, Map.of("mappings", mapper.convertValue(mappings, Map.class)));
        long expected = count(previous);
        JsonNode reindex = read("POST", "/_reindex?wait_for_completion=true&refresh=true", Map.of(
                "source", Map.of("index", previous),
                "dest", Map.of("index", target, "op_type", "create"),
                "conflicts", "proceed"));
        long indexed = count(target);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("expectedChunkCount", expected);
        report.put("indexedChunkCount", indexed);
        report.put("embeddingDimensions", mappingDimensions(mappings));
        report.put("mappingHash", Integer.toHexString(mappings.toString().hashCode()));
        report.put("versionConflicts", reindex.path("version_conflicts").asLong());
        report.put("failures", mapper.convertValue(reindex.path("failures"), List.class));
        return new PreparedIndex(readAlias, writeAlias, previous, target, expected, indexed, Map.copyOf(report));
    }

    /** 把读写别名一起切到新索引。 */
    @Override
    public void activate(String read, String write, String previous, String target) {
        switchAliases(read, write, previous, target);
    }

    /** 没有上一版索引时拒绝回滚，避免别名被摘掉后无处指向。 */
    @Override
    public void rollback(String read, String write, String current, String previous) {
        if (previous == null || previous.isBlank()) {
            throw new IllegalStateException("No previous index is available for rollback");
        }
        switchAliases(read, write, current, previous);
    }

    /**
     * 先按别名摘掉旧指向，再把两个别名加到新索引。旧索引参数目前不写入请求。
     */
    private void switchAliases(String read, String write, String oldIndex, String newIndex) {
        List<Object> actions = new ArrayList<>();
        if (hasAlias(read)) {
            actions.add(Map.of("remove", Map.of("index", "*", "alias", read, "must_exist", false)));
        }
        if (hasAlias(write)) {
            actions.add(Map.of("remove", Map.of("index", "*", "alias", write, "must_exist", false)));
        }
        actions.add(Map.of("add", Map.of("index", newIndex, "alias", read)));
        actions.add(Map.of("add", Map.of("index", newIndex, "alias", write, "is_write_index", true)));
        send("POST", "/_aliases", Map.of("actions", actions));
    }

    /** HEAD 200 才算别名存在，其他状态包括连接层包装后的异常都不是“不存在”。 */
    private boolean hasAlias(String alias) {
        HttpResponse<String> response = request("HEAD", "/_alias/" + alias, null);
        return response.statusCode() == 200;
    }

    /**
     * 优先读别名指向的具体索引。别名不存在时退回基索引；两者都没有则无法重建。
     */
    private String currentIndex() {
        HttpResponse<String> alias = request("GET", "/_alias/" + readAlias, null);
        if (alias.statusCode() == 200) {
            try {
                return mapper.readTree(alias.body()).fieldNames().next();
            } catch (Exception e) {
                throw new IllegalStateException("Invalid alias response", e);
            }
        }
        HttpResponse<String> baseResponse = request("HEAD", "/" + baseIndex, null);
        if (baseResponse.statusCode() == 200) {
            return baseIndex;
        }
        throw new IllegalStateException("No source index or read alias exists");
    }

    /** 计数接口的 count 字段；正文异常时由 {@link #read} 抛出。 */
    private long count(String index) {
        return read("GET", "/" + index + "/_count", null).path("count").asLong();
    }

    /** mapping 里没有 dims 时得到 0，调用方把它写入报告而不是在这里拒绝。 */
    private int mappingDimensions(JsonNode mappings) {
        return mappings.path("properties").path("embedding").path("dims").asInt();
    }

    /** 成功响应必须是 JSON；空正文和坏 JSON 都算治理响应无效。 */
    private JsonNode read(String method, String path, Object body) {
        try {
            return mapper.readTree(send(method, path, body));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid Elasticsearch governance response", e);
        }
    }

    /** 非 2xx 截取最多 300 字符，避免异常里带上整份集群错误。 */
    private String send(String method, String path, Object body) {
        HttpResponse<String> response = request(method, path, body);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String responseBody = response.body();
            throw new IllegalStateException("Elasticsearch governance HTTP " + response.statusCode() + ": "
                    + responseBody.substring(0, Math.min(300, responseBody.length())));
        }
        return response.body();
    }

    /** 超时与连接失败不分开编码。 */
    private HttpResponse<String> request(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout);
            if (authorization != null) {
                builder.header("Authorization", authorization);
            }
            String json = body == null ? "" : mapper.writeValueAsString(body);
            if (body != null) {
                builder.header("Content-Type", "application/json");
            }
            HttpRequest.BodyPublisher publisher = json.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json);
            builder.method(method, publisher);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Elasticsearch governance request failed", e);
        }
    }
}
