package com.jijing.fund.bootstrap;

import com.jijing.fund.agent.api.*;
import com.jijing.fund.domain.identity.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 显式打开的真实模型冒烟测试，确认一次基金工具调用能走完。默认构建不执行。
 */
@SpringBootTest(properties="fund.agent.enabled=true")
@EnabledIfEnvironmentVariable(named="RUN_REAL_MODEL_SMOKE_TEST",matches="true")
class RealModelAgentSmokeTest {
    @Autowired FundAgentUseCase agent;

    /**
     * 创建对话并询问基金资料，答案和证据都不能为空。
     */
    @Test void realModelCompletesOneFundToolCallingLoop(){var user=new AuthenticatedUser(new UserId("00000000-0000-0000-0000-000000000001"),Set.of(UserRole.USER),"real-model-smoke");ConversationResult conversation=agent.createConversation(user);FundAgentResponse response=agent.chat(new FundAgentRequest(conversation.conversationId(),"请查询 000001 的基本资料，并引用工具证据。","real-model-smoke",user));assertThat(response.answer()).isNotBlank();assertThat(response.evidence()).isNotEmpty();}
}
