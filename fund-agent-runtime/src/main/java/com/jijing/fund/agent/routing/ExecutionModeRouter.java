package com.jijing.fund.agent.routing;

import com.jijing.fund.agent.exception.AgentPolicyViolationException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 按固定规则选择执行模式。模型只能提供特征，不能改写安全规则。
 * 缺少执行权限时抛出策略异常，拒绝路由，而不是降成更便宜的模式。
 * 语义顾问失败或返回空建议时失败开放到有界 ReAct。计划校验、审批拒绝和对等代理失败发生在路由之后。
 */
public final class ExecutionModeRouter {
    public static final String VERSION = "hybrid-router-v4";
    private static final Pattern FUND = Pattern.compile("\\d{6}");
    private final RouteAdvisor advisor;

    /**
     * 创建不调用模型的路由器。
     * 语义建议始终为空，未命中强制规则的请求走有界 ReAct。
     */
    public ExecutionModeRouter() {
        this((message, features) -> Optional.empty());
    }

    /**
     * 创建带语义顾问的路由器。
     * 顾问为 null 时立即失败，避免运行中把缺失顾问误当成未知路由。
     */
    public ExecutionModeRouter(RouteAdvisor advisor) {
        this.advisor = Objects.requireNonNull(advisor, "advisor is required");
    }

    /**
     * 为一条用户消息选择执行模式。
     * 没有执行权限时抛出 {@link AgentPolicyViolationException}，不返回决策。
     * 后台、审批、副作用、报告、多阶段或自适应研究命中强制规则，进入计划执行且不调用顾问。
     * 顾问抛出运行时异常或返回空时，按短交互或澄清规则走有界 ReAct，不发明未知模式。
     */
    public RouteDecision route(String message, boolean hasPermission) {
        String text = message == null ? "" : message;
        // 缺少权限是拒绝，不是更便宜的执行模式。若把它送到直接模式，导出请求会看起来已经正常完成。
        if (!hasPermission) {
            throw new AgentPolicyViolationException("agent execution permission is required");
        }
        RouteFeatures features = features(text);
        if (features.backgroundRequested() || features.approvalRequired() || features.sideEffectRequested()) {
            return decision(ExecutionMode.PLAN_AND_EXECUTE, features, "DURABLE_OR_APPROVAL_REQUIRED");
        }
        if (features.reportRequested() || features.estimatedStages() >= 2) {
            return decision(ExecutionMode.PLAN_AND_EXECUTE, features, "MULTI_STAGE_OR_REPORT");
        }
        if (features.adaptiveResearchRequired()) {
            return decision(ExecutionMode.PLAN_AND_EXECUTE, features, "ADAPTIVE_RESEARCH_REQUIRED");
        }
        Optional<RouteAdvice> advice;
        try {
            advice = advisor.advise(text, features);
        } catch (RuntimeException ignored) {
            advice = Optional.empty();
        }
        if (advice.isPresent()) {
            RouteAdvice value = advice.get();
            RouteFeatures enriched = features.withSemanticAdvice(value);
            if (semanticRequiresPlan(value)) {
                return semanticDecision(enriched, value, "SEMANTIC_COMPLEXITY");
            }
            return decision(ExecutionMode.BOUNDED_REACT, enriched,
                    features.clarificationRequired() ? "CLARIFICATION_IN_CHAT" : "SEMANTIC_BOUNDED_TASK");
        }
        return decision(ExecutionMode.BOUNDED_REACT, features,
                features.clarificationRequired() ? "CLARIFICATION_IN_CHAT" : "SHORT_INTERACTIVE_TASK");
    }

    /**
     * 从原文提取确定性特征，语义计数字段全部置零。
     * 原文为 null 时按空字符串处理，得到需要澄清的短请求，而不是抛出未知路由。
     * 极短文本，或在没有基金代码时出现含糊说法，会标记为需要澄清。
     */
    public RouteFeatures features(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        int funds = (int) FUND.matcher(message == null ? "" : message).results().count();
        boolean personal = text.contains("我的") || text.contains("组合") || text.contains("自选") || text.contains("持仓");
        boolean document = text.contains("公告") || text.contains("季报") || text.contains("招募") || text.contains("文档");
        boolean market = text.contains("为什么") || text.contains("下跌") || text.contains("行情")
                || text.contains("催化") || text.contains("板块");
        boolean report = text.contains("生成报告") || text.contains("研究报告") || text.contains("月报") || text.contains("周报");
        boolean export = text.contains("导出") || text.contains("通知") || text.contains("发布");
        boolean background = text.contains("后台") || text.contains("稍后");
        boolean adaptive = containsAny(text, "深度研究", "深入研究", "全面研究", "催化", "归因", "多份资料",
                "证据是否充分", "风格是否发生变化");
        boolean clarification = text.length() < 4
                || (containsAny(text, "随便看看", "帮我分析一下", "哪个好") && funds == 0);
        int intents = 0;
        if (personal) {
            intents++;
        }
        if (document) {
            intents++;
        }
        if (market) {
            intents++;
        }
        if (report) {
            intents++;
        }
        if (funds > 0) {
            intents++;
        }
        int estimated = funds > 0 || personal ? 1 : 0;
        if (market) {
            estimated += 2;
        }
        if (document) {
            estimated += 1;
        }
        if (report) {
            estimated += 3;
        }
        boolean dependent = containsAny(text, "并结合", "然后", "再根据", "并生成", "综合") && intents >= 2;
        int stages = report ? Math.max(2, intents) : dependent ? 2 : 1;
        return new RouteFeatures(funds, Math.max(1, intents), estimated, stages, personal, document, market, report,
                export, export, background, adaptive, clarification, 0, 0, 0, false, false, false);
    }

    /**
     * 组装一条没有模型建议的确定性决策。
     * 直接变体保持为空，避免失败路径重新启用已废弃的直接模式。
     */
    private RouteDecision decision(ExecutionMode mode, RouteFeatures features, String rule) {
        return new RouteDecision(mode, null, VERSION, features, rule, null, null);
    }

    /**
     * 组装一条由语义复杂度抬升到计划执行的决策。
     * 建议理由写入覆盖原因，便于审计；建议不可用时不得调用本方法。
     */
    private RouteDecision semanticDecision(RouteFeatures features, RouteAdvice advice, String rule) {
        return new RouteDecision(ExecutionMode.PLAN_AND_EXECUTE, null, VERSION, features, rule,
                "SEMANTIC_FEATURES", advice.rationale());
    }

    /**
     * 判断语义特征是否足以离开有界 ReAct。
     * 需要相互依赖的多个目标、跨来源核验、迭代研究，或阶段数不少于 3。
     * 空目标或单阶段建议返回 false，请求留在有界 ReAct，不当成未知路由。
     */
    private boolean semanticRequiresPlan(RouteAdvice advice) {
        boolean dependentGoals = advice.goals().size() >= 2 && advice.hasDependencies() && advice.estimatedStages() >= 2;
        boolean crossSource = advice.crossSourceVerificationRequired() && advice.requiredCapabilities().size() >= 2;
        boolean iterative = advice.iterativeResearchRequired() && advice.estimatedStages() >= 2;
        return dependentGoals || crossSource || iterative || advice.estimatedStages() >= 3;
    }

    /**
     * 判断文本是否包含任一关键词。
     * 都不包含时返回 false，不抛出路由或计划异常。
     */
    private boolean containsAny(String text, String... values) {
        return Arrays.stream(values).anyMatch(text::contains);
    }
}
