package com.jijing.fund.knowledge.port;

import com.jijing.fund.knowledge.domain.ParsedDocument;

/**
 * 把原始字节解析成分页文本。字符总数为 0 时，入库流程停在需要 OCR，并且不重试。
 * 只含空白的正文长度大于 0，会进入切块；若切完仍为空，则成为不可重试的无效文档。
 * 损坏或不受支持的文件应在异常消息中分别带上 {@code CORRUPT} 或 {@code UNSUPPORTED}，
 * 这样会被分类成最终失败，而不是可重试的依赖错误。
 */
public interface DocumentParser {
    /**
     * 解析一份原始文件。返回的页列表可以为空，调用方用字符总数区分空文档。
     */
    ParsedDocument parse(String contentType, String fileName, byte[] content);
}
