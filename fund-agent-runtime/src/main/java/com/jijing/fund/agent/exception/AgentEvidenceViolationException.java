package com.jijing.fund.agent.exception;

/**
 * 回答或工具结果引用了无法核验的证据时抛出。
 * 只携带说明，不附带原因异常；调用方应停止对外发布该结论。
 */
public class AgentEvidenceViolationException extends RuntimeException {

    /**
     * 用调用方给出的说明构造证据违规。
     * 说明为空时仍会抛出，消息本身为空。
     */
    public AgentEvidenceViolationException(String message) {
        super(message);
    }
}
