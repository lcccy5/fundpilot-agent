package com.jijing.fund.agent.exception;

/** 表示 Agent 运行时发生 ConversationNotFoundException 所描述的失败情形。 */
public class ConversationNotFoundException extends RuntimeException {
    
    /** 执行该 Agent 运行时组件中的 ConversationNotFoundException 操作。 */
    public ConversationNotFoundException(String id) { super("Agent conversation not found: " + id); }
}
