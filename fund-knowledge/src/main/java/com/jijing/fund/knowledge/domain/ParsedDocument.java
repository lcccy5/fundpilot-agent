package com.jijing.fund.knowledge.domain;

import java.util.List;

public record ParsedDocument(List<ParsedPage> pages, List<String> warnings) {
    public ParsedDocument {
        pages = pages == null ? List.of() : List.copyOf(pages);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
    public long textCharacters(){return pages.stream().mapToLong(p->p.content()==null?0:p.content().length()).sum();}
}
