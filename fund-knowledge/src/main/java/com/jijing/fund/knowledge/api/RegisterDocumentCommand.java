package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.FundDocumentType;
import java.net.URI;
import java.time.LocalDate;
import java.util.Set;

/**
 * 注册一篇原始文档的命令。基金代码为 null 时收成空集。
 * 正文为 null 时收成空数组，随后会被入库校验拒绝，不会被当成可索引的空文档悄悄收下。
 * 外部文档号和来源 URI 至少要有一个非空值，重复入库才有幂等键；本命令不比较历史内容。
 * 来源 URI 允许为 null，只要外部文档号有内容。缺少来源 URI 不影响注册，切片上的引用也会是 null。
 */
public record RegisterDocumentCommand(
        String externalDocumentId,
        String title,
        FundDocumentType documentType,
        String publisher,
        String sourceName,
        URI sourceUri,
        LocalDate publishedDate,
        Set<String> fundCodes,
        String originalFileName,
        String contentType,
        byte[] content) {
    /**
     * 复制基金代码和正文，避免调用方在注册过程中改写数组。null 正文变成空数组。
     */
    public RegisterDocumentCommand {
        fundCodes = fundCodes == null ? Set.of() : Set.copyOf(fundCodes);
        content = content == null ? new byte[0] : content.clone();
    }

    /**
     * 再复制一份正文，避免读取方改到记录内部的数组。
     */
    @Override
    public byte[] content() {
        return content.clone();
    }
}
