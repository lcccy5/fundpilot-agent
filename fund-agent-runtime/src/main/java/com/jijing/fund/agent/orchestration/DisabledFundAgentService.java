package com.jijing.fund.agent.orchestration;

import com.jijing.fund.agent.api.ConversationResult;
import com.jijing.fund.agent.api.FundAgentEvent;
import com.jijing.fund.agent.api.FundAgentRequest;
import com.jijing.fund.agent.api.FundAgentResponse;
import com.jijing.fund.agent.api.FundAgentUseCase;
import com.jijing.fund.agent.exception.AgentDisabledException;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 在 Agent 未启用时占位的用例实现。
 * 对话、同步回答和流式回答一律抛出或发出 {@link AgentDisabledException}，不创建计划、不路由、不发起审批，也不调用对等代理。
 */
@Service
@ConditionalOnProperty(prefix = "fund.agent", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledFundAgentService implements FundAgentUseCase {

    /**
     * 拒绝同步回答。
     * 不读取请求内容，因此计划、路由、审批或对等代理都不会开始。
     */
    @Override
    public FundAgentResponse chat(FundAgentRequest request) {
        throw new AgentDisabledException();
    }

    /**
     * 拒绝流式回答。
     * 返回立即失败的流，订阅方看到的是代理未启用，而不是空的成功答案。
     */
    @Override
    public Flux<FundAgentEvent> stream(FundAgentRequest request) {
        return Flux.error(new AgentDisabledException());
    }

    /**
     * 拒绝创建会话。
     * 未启用时不分配会话标识，后续路由和计划都无从开始。
     */
    @Override
    public ConversationResult createConversation(AuthenticatedUser actor) {
        throw new AgentDisabledException();
    }
}
