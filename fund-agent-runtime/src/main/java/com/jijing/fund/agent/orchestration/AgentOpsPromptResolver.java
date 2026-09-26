package com.jijing.fund.agent.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jijing.fund.agent.api.FundAgentRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 从 AgentOps 按请求解析提示词，并用五秒缓存减少重复拉取。
 * 控制面不可用且请求没有强制版本时，回退到本地稳定提示词。
 * 强制版本解析失败时失败关闭，抛出 {@link IllegalStateException}，本次运行不得改用另一份提示词做评估。
 * 计划、路由、审批或对等代理失败不刷新这里的缓存。
 */
@Component
@ConditionalOnProperty(prefix = "fund.agent.agentops", name = "enabled", havingValue = "true")
public final class AgentOpsPromptResolver implements FundAgentPromptResolver {
    private final RestClient client;
    private final FundAgentPrompt fallback;
    private final String environment;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * 绑定 AgentOps 控制面和本地回退提示词。
     * 地址或令牌无效不会在构造期探测；第一次解析失败时才按是否强制版本决定回退或拒绝。
     */
    public AgentOpsPromptResolver(RestClient.Builder builder, FundAgentPrompt fallback,
            @Value("${fund.agent.agentops.base-url:http://localhost:18080}") String baseUrl,
            @Value("${fund.agent.agentops.admin-token:local-admin-token}") String adminToken,
            @Value("${fund.agent.agentops.environment:local}") String environment) {
        this.client = builder.baseUrl(baseUrl).defaultHeader("X-AgentOps-Admin-Token", adminToken).build();
        this.fallback = fallback;
        this.environment = environment;
    }

    /**
     * 解析并缓存主体与强制版本组成的提示词。
     * 缓存未过期时直接返回。响应为空时视为失败。强制版本无法解析时把原因包进 {@link IllegalStateException} 并拒绝回退。
     * 未强制版本时返回本地稳定提示词。
     */
    @Override
    public ResolvedFundAgentPrompt resolve(FundAgentRequest request) {
        String subject = request.actor() == null ? request.conversationId() : request.actor().userId().value();
        String key = subject + ":" + String.valueOf(request.forcedPromptVersion());
        Cached existing = cache.get(key);
        if (existing != null && existing.expiresAt().isAfter(Instant.now())) {
            return existing.prompt();
        }
        try {
            Resolution body = client.post()
                    .uri("/internal/v1/prompts/resolvePrompt/fund-agent-system")
                    .body(Map.of("environment", environment, "subjectKey", subject,
                            "forcedVersion", request.forcedPromptVersion() == null ? "" : request.forcedPromptVersion()))
                    .retrieve()
                    .body(Resolution.class);
            if (body == null || body.template() == null || body.template().isBlank()) {
                throw new IllegalStateException("AgentOps returned an empty prompt");
            }
            ResolvedFundAgentPrompt resolved = new ResolvedFundAgentPrompt(body.promptVersion(), body.template(),
                    body.templateHash(), body.releaseId(), body.variant());
            cache.put(key, new Cached(resolved, Instant.now().plus(Duration.ofSeconds(5))));
            return resolved;
        } catch (RuntimeException unavailable) {
            if (request.forcedPromptVersion() != null && !request.forcedPromptVersion().isBlank()) {
                throw new IllegalStateException(
                        "Forced AgentOps prompt version could not be resolved: " + request.forcedPromptVersion(),
                        unavailable);
            }
            return ResolvedFundAgentPrompt.local(fallback);
        }
    }

    /**
     * AgentOps 解析响应中本次需要的字段。
     * 未知字段被忽略。模板为空时由 {@link #resolve} 拒绝，不把空提示词写入缓存。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Resolution(String promptVersion, String releaseId, String variant, String template,
            String templateHash) {
    }

    /**
     * 一条尚未过期的解析结果。
     * 过期后下一次解析会重新请求；请求失败且未强制版本时不更新该缓存。
     */
    private record Cached(ResolvedFundAgentPrompt prompt, Instant expiresAt) {
    }
}
