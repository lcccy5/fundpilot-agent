package com.jijing.fund.application.exception;

public class FundNotFoundException extends RuntimeException {
    public FundNotFoundException(String fundCode) { super("Fund not found: " + fundCode); }
}

