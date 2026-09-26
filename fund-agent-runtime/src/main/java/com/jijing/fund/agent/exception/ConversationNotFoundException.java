package com.jijing.fund.agent.exception;

/**
 * 按标识找不到对话时抛出，避免在不存在的会话上继续读写。
 * 消息会带上调用方传入的标识，标识为空时消息中对应位置也为空。
 */
public class ConversationNotFoundException extends RuntimeException {

    /**
     * 把缺失的对话标识写入异常消息。
     * 不检查标识格式，也不区分“不存在”和“不属于当前用户”。
     */
    public ConversationNotFoundException(String id) {
        super("Agent conversation not found: " + id);
    }
}
