package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.FundDocumentType;
import java.net.URI;
import java.time.LocalDate;
import java.util.Set;

public record RegisterDocumentCommand(String externalDocumentId, String title, FundDocumentType documentType,
        String publisher, String sourceName, URI sourceUri, LocalDate publishedDate, Set<String> fundCodes,
        String originalFileName, String contentType, byte[] content) {
    public RegisterDocumentCommand {
        fundCodes = fundCodes == null ? Set.of() : Set.copyOf(fundCodes);
        content = content == null ? new byte[0] : content.clone();
    }
    @Override public byte[] content(){return content.clone();}
}
