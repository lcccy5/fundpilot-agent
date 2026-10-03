package com.jijing.fund.infrastructure.outbox;

import com.jijing.fund.agent.event.DomainEvent;
import com.jijing.fund.agent.notification.NotificationStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Java 只运输已提交事件并保存通知，是否触发通知由 Python 判断。 */
@Component
public class PythonAgentEventClient {
    private final RestClient client;
    private final NotificationStore notifications;
    private final String secret;
    private final Path secretFile;

    public PythonAgentEventClient(NotificationStore notifications,
            @Value("${fund.agent.python-base-url:http://127.0.0.1:8001}") String baseUrl,
            @Value("${AGENT_EVENT_SECRET:}") String secret,
            @Value("${fund.agent.event-secret-file:python-agent/data/agent-event.key}") String secretFile) {
        this.notifications = notifications;
        this.secret = secret;
        this.secretFile = Path.of(secretFile);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Decision dispatch(DomainEvent event, Instant now) {
        try {
            String key = secret.isBlank() ? readLocalKey() : secret;
            Decision decision = client.post().uri("/internal/agent/events")
                    .header("X-Agent-Event-Key", key).body(event).retrieve().body(Decision.class);
            if (decision == null) throw new IllegalStateException("Python 未返回事件处理结果");
            if (decision.shouldNotify() || decision.held()) {
                // 同一事件重试时 Python 返回同一 fingerprint，Java 的唯一约束阻止重复通知。
                notifications.record(decision.ownerUserId(), decision.ruleId(), decision.fingerprint(), decision.held(), now);
            }
            return decision;
        } catch (Exception error) {
            // 不把共享凭证或 HTTP 请求正文放进日志。
            throw new IllegalStateException("Python Agent 事件服务暂不可用");
        }
    }

    private String readLocalKey() throws java.io.IOException {
        if (Files.exists(secretFile)) return Files.readString(secretFile).trim();
        // Maven 可能从 fund-bootstrap 模块启动，向上寻找工作区共享凭证。
        Path directory = Path.of("").toAbsolutePath();
        for (int level = 0; level < 5 && directory != null; level++, directory = directory.getParent()) {
            Path candidate = directory.resolve(secretFile);
            if (Files.exists(candidate)) return Files.readString(candidate).trim();
        }
        throw new java.io.IOException("业务事件凭证尚未生成，请先启动 Python 服务");
    }

    public record Decision(boolean deadLetter, String reason, boolean shouldNotify, boolean held,
                           String ownerUserId, String ruleId, String fingerprint) {}
}
