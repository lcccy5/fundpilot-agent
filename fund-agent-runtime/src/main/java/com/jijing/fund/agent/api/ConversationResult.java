package com.jijing.fund.agent.api;

import java.time.Instant;

/**
 * 新建对话后返回的标识和创建时间。
 * 不校验标识格式；创建时间为空时表示实现没有记录时间。
 */
public record ConversationResult(String conversationId, Instant createdAt) {
}
