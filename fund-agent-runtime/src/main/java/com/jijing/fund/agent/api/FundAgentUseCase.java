package com.jijing.fund.agent.api;

import com.jijing.fund.domain.identity.AuthenticatedUser;
import reactor.core.publisher.Flux;

/**
 * 面向对话的智能体入口：同步回答、流式事件和创建会话。
 * 请求不合法、模型不可用或策略拒绝时，实现应失败而不是返回空答案冒充成功。
 */
public interface FundAgentUseCase {

    /**
     * 同步完成一轮对话并返回最终答案。
     * 请求为空、主体缺失或执行被拒绝时失败；失败不保证已经写入运行记录。
     */
    FundAgentResponse chat(FundAgentRequest request);

    /**
     * 以事件流推送同一轮对话的进度和最终结果。
     * 请求无法执行时，流中应出现失败事件或直接以错误结束，而不是静默完成。
     */
    Flux<FundAgentEvent> stream(FundAgentRequest request);

    /**
     * 为当前主体创建一个新会话。
     * 主体为空或持久化失败时失败，不返回无法再次定位的会话。
     */
    ConversationResult createConversation(AuthenticatedUser actor);
}
