package com.jijing.fund.agent.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jijing.fund.agent.api.FundAgentRequest;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Fetches request-level prompts from AgentOps with a five-second cache and explicit local stable fallback. */
@Component
@ConditionalOnProperty(prefix = "fund.agent.agentops", name = "enabled", havingValue = "true")
/** 实现 AgentOpsPromptResolver 所代表的 Agent 运行时职责。 */
public final class AgentOpsPromptResolver implements FundAgentPromptResolver {
    private final RestClient client;
    private final FundAgentPrompt fallback;
    private final String environment;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** Creates a resolver bound to one AgentOps control plane. */
    public AgentOpsPromptResolver(RestClient.Builder builder, FundAgentPrompt fallback,
                                  @Value("${fund.agent.agentops.base-url:http://localhost:18080}") String baseUrl,
                                  @Value("${fund.agent.agentops.admin-token:local-admin-token}") String adminToken,
                                  @Value("${fund.agent.agentops.environment:local}") String environment) {
        this.client = builder.baseUrl(baseUrl).defaultHeader("X-AgentOps-Admin-Token", adminToken).build();
        this.fallback = fallback; this.environment = environment;
    }

    /** Resolves and caches a subject/version tuple; forced evaluation versions fail closed instead of testing a fallback. */
    @Override 
    /** 构造后续 Agent 处理所需的 resolve 值。 */
    public ResolvedFundAgentPrompt resolve(FundAgentRequest request) {
        String subject = request.actor() == null ? request.conversationId() : request.actor().userId().value();
        String key = subject + ":" + String.valueOf(request.forcedPromptVersion()); Cached existing = cache.get(key);
        if (existing != null && existing.expiresAt().isAfter(Instant.now())) return existing.prompt();
        try {
            Resolution body = client.post().uri("/internal/v1/prompts/resolvePrompt/fund-agent-system")
                    .body(Map.of("environment", environment, "subjectKey", subject,
                            "forcedVersion", request.forcedPromptVersion() == null ? "" : request.forcedPromptVersion()))
                    .retrieve().body(Resolution.class);
            if (body == null || body.template() == null || body.template().isBlank()) throw new IllegalStateException("AgentOps returned an empty prompt");
            ResolvedFundAgentPrompt resolved = new ResolvedFundAgentPrompt(body.promptVersion(), body.template(), body.templateHash(), body.releaseId(), body.variant());
            cache.put(key, new Cached(resolved, Instant.now().plus(Duration.ofSeconds(5)))); return resolved;
        } catch (RuntimeException unavailable) {
            if (request.forcedPromptVersion() != null && !request.forcedPromptVersion().isBlank()) {
                throw new IllegalStateException("Forced AgentOps prompt version could not be resolved: " + request.forcedPromptVersion(), unavailable);
            }
            return ResolvedFundAgentPrompt.local(fallback);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    
    /** 在 Agent 运行时边界间传递 Resolution 数据的不可变值对象。 */
    private record Resolution(String promptVersion, String releaseId, String variant, String template, String templateHash) { }
    
    /** 在 Agent 运行时边界间传递 Cached 数据的不可变值对象。 */
    private record Cached(ResolvedFundAgentPrompt prompt, Instant expiresAt) { }
}
