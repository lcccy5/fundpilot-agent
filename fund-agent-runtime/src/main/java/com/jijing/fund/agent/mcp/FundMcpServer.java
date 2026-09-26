package com.jijing.fund.agent.mcp;

import com.jijing.fund.agent.planning.AgentCapabilityRegistry;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 内部只读 MCP 入口。个人工具使用服务端认证上下文，参数里不允许出现 userId。
 * 参数、模式或能力不合法时先记拒绝审计再抛出异常，不返回证据编号。
 */
public final class FundMcpServer {
    public static final Map<String, String> TOOLS = Map.of(
            "get_fund_profile", "FUND_PROFILE_QUERY",
            "get_fund_nav_history", "FUND_NAV_QUERY",
            "calculate_fund_metrics", "FUND_METRICS_QUERY",
            "compare_fund_metrics", "FUND_COMPARE",
            "search_fund_documents", "DOCUMENT_SEARCH",
            "get_my_portfolio", "PORTFOLIO_SNAPSHOT",
            "analyze_my_portfolio_risk", "PORTFOLIO_RISK");
    private final McpGovernance governance = new McpGovernance();
    private final McpCallAuditor auditor;
    private final String schemaHash;

    /**
     * 使用不写审计的空记录器。模式摘要仍按当前工具表计算。
     */
    public FundMcpServer() {
        this(McpCallAuditor.NOOP);
    }

    /**
     * 绑定调用审计器。传入 null 时退回空记录器，避免调用成功却在审计处空指针。
     * 模式摘要由工具名和 capability-v1 计算；摘要算法不可用时抛出 IllegalStateException。
     */
    public FundMcpServer(McpCallAuditor auditor) {
        this.auditor = auditor == null ? McpCallAuditor.NOOP : auditor;
        this.schemaHash = hash(String.join("|", new TreeSet<>(TOOLS.keySet())) + "|capability-v1");
    }

    /**
     * 返回当前工具表的模式摘要。构造已成功时不会失败。
     */
    public String schemaHash() {
        return schemaHash;
    }

    /**
     * 返回已发布的工具名。不包含需要审批的副作用工具。
     */
    public Set<String> tools() {
        return TOOLS.keySet();
    }

    /**
     * 按工具名解析白名单能力。参数含 userId、模式摘要变化、工具未知、个人工具缺少登录用户、
     * 能力需要审批或正文被治理拒绝时，记录 REJECTED 并抛出对应的运行时异常。
     * 成功时记录 SUCCESS，并返回带工具名的证据编号；该编号只标识这次 MCP 调用，不证明外部正文可信。
     */
    public McpCallResult invoke(String tool, Map<String, Object> arguments, AuthenticatedUser user, String storedSchemaHash) {
        try {
            if (arguments != null && arguments.containsKey("userId")) {
                throw new IllegalArgumentException("userId is not allowed in MCP arguments");
            }
            if (governance.shouldPausePlan(storedSchemaHash, schemaHash)) {
                throw new IllegalStateException("mcp schema changed; plan paused");
            }
            String capability = TOOLS.get(tool);
            if (capability == null || !AgentCapabilityRegistry.WHITELIST.contains(capability)) {
                throw new IllegalArgumentException("unknown or unlisted MCP tool");
            }
            boolean personal = capability.startsWith("PORTFOLIO") || "WATCHLIST_READ".equals(capability);
            if (personal && user == null) {
                throw new IllegalArgumentException("authenticated user is required");
            }
            if (AgentCapabilityRegistry.APPROVAL_REQUIRED.contains(capability)) {
                throw new IllegalStateException("side-effect tools require V4 approval");
            }
            String content = String.valueOf(arguments == null ? Map.of() : arguments);
            if (!governance.acceptExternalContent(content)) {
                throw new IllegalArgumentException("external content rejected");
            }
            auditor.record(null, null, capability, schemaHash, "SUCCESS", null, java.time.Instant.now());
            return new McpCallResult(tool, capability, "ev-mcp-" + tool, schemaHash);
        } catch (RuntimeException e) {
            auditor.record(null, null, tool, schemaHash, "REJECTED", e.getClass().getSimpleName(), java.time.Instant.now());
            throw e;
        }
    }

    /**
     * 计算工具表摘要。SHA-256 不可用时抛出 IllegalStateException，服务器不能带着空摘要启动。
     */
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 一次允许执行的 MCP 调用。evidenceId 只标识工具入口；参数被拒绝时不会产生该结果。
     */
    public record McpCallResult(String tool, String capability, String evidenceId, String schemaHash) {}
}
