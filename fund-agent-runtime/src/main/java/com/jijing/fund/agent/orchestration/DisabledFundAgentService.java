package com.jijing.fund.agent.orchestration;

import com.jijing.fund.agent.api.*;
import com.jijing.fund.agent.exception.AgentDisabledException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@ConditionalOnProperty(prefix="fund.agent",name="enabled",havingValue="false",matchIfMissing=true)
/** 实现 DisabledFundAgentService 所代表的 Agent 运行时职责。 */
public class DisabledFundAgentService implements FundAgentUseCase {
    @Override 
    /** 执行该 Agent 运行时组件中的 chat 操作。 */
    public FundAgentResponse chat(FundAgentRequest request){throw new AgentDisabledException();}
    @Override 
    /** 执行该 Agent 运行时组件中的 stream 操作。 */
    public Flux<FundAgentEvent> stream(FundAgentRequest request){return Flux.error(new AgentDisabledException());}
    @Override 
    /** 创建并初始化当前 Agent 操作所需的 createConversation 结果。 */
    public ConversationResult createConversation(com.jijing.fund.domain.identity.AuthenticatedUser actor){throw new AgentDisabledException();}
}
