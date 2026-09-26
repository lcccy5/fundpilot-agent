package com.jijing.fund.knowledge.domain;

import java.util.List;

/**
 * 解析器产出的分页文本。
 * 页列表或警告为 null 时视为空列表，调用方不必用 null 表示没有正文。
 * 字符总数为 0 才被入库流程当成抽不出文本；只含空白的正文长度大于 0，会继续进入切块。
 */
public record ParsedDocument(List<ParsedPage> pages, List<String> warnings) {
    /**
     * 复制列表。null 收成不可变空列表，避免后续切块时遇到 null 集合。
     */
    public ParsedDocument {
        pages = pages == null ? List.of() : List.copyOf(pages);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * 各页正文的 UTF-16 长度之和。某一页正文为 null 时该页计 0，空白字符会计入。
     */
    public long textCharacters() {
        return pages.stream().mapToLong(page -> page.content() == null ? 0 : page.content().length()).sum();
    }
}
