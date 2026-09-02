package com.jijing.fund.application.exception;

public class SyncAlreadyRunningException extends RuntimeException {
    public SyncAlreadyRunningException(String code) { super("Sync already running for fund: " + code); }
}

