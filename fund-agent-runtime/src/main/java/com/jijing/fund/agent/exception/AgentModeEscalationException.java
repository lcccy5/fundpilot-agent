package com.jijing.fund.agent.exception;

/** Signals that a bounded read-only run needs more tool budget and may be promoted once. */
public final class AgentModeEscalationException extends AgentExecutionLimitException {
    public AgentModeEscalationException(String message){super(message);}
}
