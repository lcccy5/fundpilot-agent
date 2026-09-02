package com.jijing.fund.domain.portfolio;
public record ImportRow(int sourceRowNumber,String rawJson,String errorCode,String safeMessage) {}
