package com.jijing.fund.bootstrap;

import com.jijing.fund.agent.api.*;
import com.jijing.fund.domain.identity.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit, opt-in smoke test. It is never executed by the default build. */
@SpringBootTest(properties="fund.agent.enabled=true")
@EnabledIfEnvironmentVariable(named="RUN_REAL_MODEL_SMOKE_TEST",matches="true")
class RealModelAgentSmokeTest {
    @Autowired FundAgentUseCase agent;
    @Test void realModelCompletesOneFundToolCallingLoop(){var user=new AuthenticatedUser(new UserId("00000000-0000-0000-0000-000000000001"),Set.of(UserRole.USER),"real-model-smoke");ConversationResult conversation=agent.createConversation(user);FundAgentResponse response=agent.chat(new FundAgentRequest(conversation.conversationId(),"请查询 000001 的基本资料，并引用工具证据。","real-model-smoke",user));assertThat(response.answer()).isNotBlank();assertThat(response.evidence()).isNotEmpty();}
}
