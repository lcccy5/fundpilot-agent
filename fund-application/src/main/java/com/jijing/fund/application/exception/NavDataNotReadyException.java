package com.jijing.fund.application.exception;

public class NavDataNotReadyException extends RuntimeException {
    public NavDataNotReadyException(String code) { super("NAV data is not ready for fund: " + code); }
}

