package com.jijing.fund.domain.portfolio;

/**
 * 导入文件中的一行及其校验结果：源文件行号、原始内容（JSON）、错误码和可安全展示给用户的错误说明；
 * 错误码为 null 通常表示该行有效。构造时不做任何校验。
 */
public record ImportRow(int sourceRowNumber, String rawJson, String errorCode, String safeMessage) {}
