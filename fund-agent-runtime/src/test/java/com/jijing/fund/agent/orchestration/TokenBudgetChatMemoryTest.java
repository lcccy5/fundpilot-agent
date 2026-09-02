package com.jijing.fund.agent.orchestration;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetChatMemoryTest {
    @Test void keepsNewestCompleteConversationWithinTokenBudget(){
        var repository=new InMemoryChatMemoryRepository();
        var memory=new TokenBudgetChatMemory(repository,18,20);
        memory.add("conversation",List.of(new UserMessage("这是很早以前的一条很长的问题消息"),new AssistantMessage("这是很早以前的回答")));
        memory.add("conversation",List.of(new UserMessage("最近问题"),new AssistantMessage("最近回答")));
        assertThat(memory.get("conversation")).extracting(Message::getText).containsExactly("最近问题","最近回答");
    }

    @Test void messageCountRemainsAnIndependentSafetyCap(){
        var memory=new TokenBudgetChatMemory(new InMemoryChatMemoryRepository(),1000,2);
        memory.add("conversation",List.of(new UserMessage("第一问"),new AssistantMessage("第一答"),new UserMessage("第二问"),new AssistantMessage("第二答")));
        assertThat(memory.get("conversation")).extracting(Message::getText).containsExactly("第二问","第二答");
    }
}
