package com.jijing.fund.knowledge.exception;

public class KnowledgeInvalidArgumentException extends RuntimeException {
    public KnowledgeInvalidArgumentException(String message){super(message);}
    public KnowledgeInvalidArgumentException(String message,Throwable cause){super(message,cause);}
}
