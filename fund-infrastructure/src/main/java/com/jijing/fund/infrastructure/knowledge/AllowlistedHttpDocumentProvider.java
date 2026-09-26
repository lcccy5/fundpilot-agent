package com.jijing.fund.infrastructure.knowledge;

import com.jijing.fund.knowledge.port.AuthorizedDocumentProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 只拉取白名单 HTTPS 文档。连接超时 3 秒，单次请求超时 20 秒，且不跟随重定向。
 * 连接失败、读超时和传输中断都是 {@link IOException}，统一抛出 {@code Document fetch failed}。
 * 线程中断会恢复中断标记并抛出 {@code Document fetch interrupted}。
 * 非 2xx、超限和不受支持的 Content-Type 在读完正文前或读的过程中拒绝。
 * 允许的类型即使正文为空也返回空字节数组，不单独报空响应。没有重复提交去重。
 */
public final class AllowlistedHttpDocumentProvider implements AuthorizedDocumentProvider {
    private static final Set<String> TYPES = Set.of("application/pdf", "text/html", "text/plain");
    private final Set<String> hosts;
    private final long maxBytes;
    private final HttpClient client;

    /** 主机名按小写保存。客户端在构造时固定超时，调用方不能按请求放宽。 */
    public AllowlistedHttpDocumentProvider(Set<String> hosts, long maxBytes) {
        this.hosts = hosts.stream().map(v -> v.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        this.maxBytes = maxBytes;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * 先做主机校验再发请求。Content-Length 超限时不读正文；实际读到的字节仍会再数一遍。
     */
    @Override
    public FetchedDocument fetch(URI uri) {
        validate(uri);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/pdf,text/html,text/plain").GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Authorized document source HTTP " + response.statusCode());
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > maxBytes) {
                throw new IllegalArgumentException("Remote document exceeds size limit");
            }
            String type = response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                throw new IllegalArgumentException("Remote document content type is not supported");
            }
            byte[] content = readLimited(response.body());
            String path = uri.getPath();
            String file = path == null || path.endsWith("/") ? "document" : path.substring(path.lastIndexOf('/') + 1);
            return new FetchedDocument(uri, file, type, content);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Document fetch interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Document fetch failed", e);
        }
    }

    /**
     * 只接受白名单上的 HTTPS 主机，并拒绝解析到回环、链路本地或站点本地地址的名字。
     * DNS 失败视为参数错误，而不是稍后的连接失败。
     */
    private void validate(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Only allowlisted HTTPS document URLs are accepted");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!hosts.contains(host)) {
            throw new IllegalArgumentException("Document source host is not allowlisted");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()) {
                    throw new IllegalArgumentException("Private network document sources are forbidden");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Document source host cannot be resolved", e);
        }
    }

    /** 流式计数，超过上限立即停止，避免先把超大文件放进内存。 */
    private byte[] readLimited(InputStream input) throws IOException {
        try (input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            for (int n; (n = input.read(buffer)) >= 0; ) {
                total += n;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("Remote document exceeds size limit");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
