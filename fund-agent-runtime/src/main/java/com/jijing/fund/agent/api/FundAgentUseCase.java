package com.jijing.fund.agent.api;

import reactor.core.publisher.Flux;
import com.jijing.fund.domain.identity.AuthenticatedUser;

/** 定义 FundAgentUseCase 在 Agent 运行时中的能力契约。 */
public interface FundAgentUseCase {
    
    /** 执行该 Agent 运行时组件中的 chat 操作。 */
    FundAgentResponse chat(FundAgentRequest request);
    
    /** 执行该 Agent 运行时组件中的 stream 操作。 */
    Flux<FundAgentEvent> stream(FundAgentRequest request);
    
    /** 创建并初始化当前 Agent 操作所需的 createConversation 结果。 */
    ConversationResult createConversation(AuthenticatedUser actor);
}
