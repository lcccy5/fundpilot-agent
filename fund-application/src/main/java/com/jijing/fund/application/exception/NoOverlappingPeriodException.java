package com.jijing.fund.application.exception;

public class NoOverlappingPeriodException extends RuntimeException {
    public NoOverlappingPeriodException() { super("Funds have no overlapping NAV period"); }
}

