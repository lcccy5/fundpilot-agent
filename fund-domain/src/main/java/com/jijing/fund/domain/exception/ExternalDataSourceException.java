package com.jijing.fund.domain.exception;

public class ExternalDataSourceException extends RuntimeException {
    private final String errorCode;
    public ExternalDataSourceException(String errorCode, String message) { super(message); this.errorCode = errorCode; }
    public ExternalDataSourceException(String errorCode, String message, Throwable cause) { super(message, cause); this.errorCode = errorCode; }
    public String errorCode() { return errorCode; }
}

