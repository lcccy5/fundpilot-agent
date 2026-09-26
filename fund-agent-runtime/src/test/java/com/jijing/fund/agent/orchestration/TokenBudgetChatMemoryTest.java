package com.jijing.fund.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.*;

/** 确认会话记忆按 token 和条数保留最近的完整回合。 预算用尽时丢掉更早的历史，而不是让写入失败。 */
class TokenBudgetChatMemoryTest {
  /** 较新的一轮对话在预算内保留，更早的长消息被裁掉。 裁剪结果仍是完整的用户与助手配对。 */
  @Test
  void keepsNewestCompleteConversationWithinTokenBudget() {
    var repository = new InMemoryChatMemoryRepository();
    var memory = new TokenBudgetChatMemory(repository, 18, 20);
    memory.add(
        "conversation",
        List.of(new UserMessage("这是很早以前的一条很长的问题消息"), new AssistantMessage("这是很早以前的回答")));
    memory.add("conversation", List.of(new UserMessage("最近问题"), new AssistantMessage("最近回答")));
    assertThat(memory.get("conversation"))
        .extracting(Message::getText)
        .containsExactly("最近问题", "最近回答");
  }

  /** 条数上限独立于 token 预算生效。 超过条数的旧回合不会因为 token 还够而留下。 */
  @Test
  void messageCountRemainsAnIndependentSafetyCap() {
    var memory = new TokenBudgetChatMemory(new InMemoryChatMemoryRepository(), 1000, 2);
    memory.add(
        "conversation",
        List.of(
            new UserMessage("第一问"),
            new AssistantMessage("第一答"),
            new UserMessage("第二问"),
            new AssistantMessage("第二答")));
    assertThat(memory.get("conversation"))
        .extracting(Message::getText)
        .containsExactly("第二问", "第二答");
  }

  /** 四条上限保留最近两轮，并丢掉更早的一轮。 不会留下没有问题的助手消息开头。 */
  @Test
  void fourMessageCapKeepsExactlyTwoCompleteRounds() {
    var memory = new TokenBudgetChatMemory(new InMemoryChatMemoryRepository(), 3000, 4);
    memory.add(
        "conversation",
        List.of(
            new UserMessage("第一问"), new AssistantMessage("第一答"),
            new UserMessage("第二问"), new AssistantMessage("第二答"),
            new UserMessage("第三问"), new AssistantMessage("第三答")));
    assertThat(memory.get("conversation"))
        .extracting(Message::getText)
        .containsExactly("第二问", "第二答", "第三问", "第三答");
  }
}
