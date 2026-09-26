package com.jijing.fund.knowledge.domain;

/**
 * 解析后的一页。标题和正文都允许为 null：切块时 null 标题写成空串，null 正文先按空串规范化再跳过。
 * 页码原样保存，本记录不要求从 1 连续。
 */
public record ParsedPage(int pageNumber, String heading, String content) {}
