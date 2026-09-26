package com.jijing.fund.agent.orchestration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;

/**
 * 按近似 token 预算保留最近对话，并用消息条数作上限。
 * 预算非正或会话标识空白时立即拒绝写入。裁剪失败不会改写计划、路由或审批；它只影响下一次模型看到的历史。
 */
public final class TokenBudgetChatMemory implements ChatMemory {
    private final ChatMemoryRepository repository;
    private final int maxTokens;
    private final int maxMessages;

    /**
     * 绑定存储和两条正数预算。
     * 任一预算小于 1 时抛出 {@link IllegalArgumentException}，避免后续裁剪把全部历史都丢掉或全部留下。
     */
    public TokenBudgetChatMemory(ChatMemoryRepository repository, int maxTokens, int maxMessages) {
        this.repository = Objects.requireNonNull(repository);
        if (maxTokens < 1 || maxMessages < 1) {
            throw new IllegalArgumentException("memory budgets must be positive");
        }
        this.maxTokens = maxTokens;
        this.maxMessages = maxMessages;
    }

    /**
     * 把消息追加到会话并按预算裁剪后保存。
     * 会话标识空白、消息列表为 null 或含 null 元素时抛出异常，不写入存储。
     * 新的系统消息会替换已有系统消息。计划或对等代理失败不会通过本方法回滚历史。
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        requireId(conversationId);
        Objects.requireNonNull(messages, "messages");
        if (messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("messages cannot contain null");
        }
        List<Message> combined = new ArrayList<>(repository.findByConversationId(conversationId));
        for (Message message : messages) {
            if (message instanceof SystemMessage) {
                combined.removeIf(SystemMessage.class::isInstance);
            }
            if (!combined.contains(message)) {
                combined.add(message);
            }
        }
        repository.saveAll(conversationId, trim(combined));
    }

    /**
     * 读取会话中已经保存的消息。
     * 会话标识空白时拒绝读取。存储失败会原样抛出，不返回空历史来掩盖错误。
     */
    @Override
    public List<Message> get(String conversationId) {
        requireId(conversationId);
        return repository.findByConversationId(conversationId);
    }

    /**
     * 删除会话历史。
     * 会话标识空白时拒绝删除，避免空键误清全部记忆。
     */
    @Override
    public void clear(String conversationId) {
        requireId(conversationId);
        repository.deleteByConversationId(conversationId);
    }

    /**
     * 保留系统消息，并从最新的普通消息向前选取，直到条数或 token 预算用尽。
     * 选中序列若以助手消息开头则丢掉它，避免留下没有用户问题的半轮。
     * 第一条普通消息即使超出预算也会保留，保证会话不是完全空白。
     */
    List<Message> trim(List<Message> input) {
        List<Message> systems = input.stream().filter(SystemMessage.class::isInstance).toList();
        List<Message> ordinary = input.stream().filter(message -> !(message instanceof SystemMessage)).toList();
        ArrayDeque<Message> selected = new ArrayDeque<>();
        int tokens = systems.stream().mapToInt(this::tokens).sum();
        for (int index = ordinary.size() - 1; index >= 0 && selected.size() < maxMessages; index--) {
            Message message = ordinary.get(index);
            int cost = tokens(message);
            if (!selected.isEmpty() && tokens + cost > maxTokens) {
                break;
            }
            if (selected.isEmpty() || tokens + cost <= maxTokens) {
                selected.addFirst(message);
                tokens += cost;
            }
        }
        while (!selected.isEmpty() && selected.getFirst().getMessageType() == MessageType.ASSISTANT) {
            selected.removeFirst();
        }
        List<Message> result = new ArrayList<>(systems);
        result.addAll(selected);
        return List.copyOf(result);
    }

    /**
     * 估算一条消息占用的 token。
     * 消息为 null 时按空文本估算为 1，不把缺失消息当成运行失败。
     */
    public int tokens(Message message) {
        return estimateTokens(message == null ? null : message.getText());
    }

    /**
     * 用码点数量的四分之三向上取整估算 token，至少为 1。
     * 空白文本返回 1。该估算只服务裁剪，不参与计划预算或审批。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        int codePoints = text.codePointCount(0, text.length());
        return Math.max(1, (codePoints * 3 + 3) / 4);
    }

    /**
     * 拒绝空白会话标识。
     * 缺失标识时抛出 {@link IllegalArgumentException}，不落到默认会话。
     */
    private void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("conversationId cannot be blank");
        }
    }
}
