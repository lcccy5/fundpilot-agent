package com.jijing.fund.knowledge.port;

import com.jijing.fund.knowledge.domain.ParsedDocument;

public interface DocumentParser {
    ParsedDocument parse(String contentType, String fileName, byte[] content);
}
