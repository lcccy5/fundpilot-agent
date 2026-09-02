package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.FundDocumentType;
import java.time.LocalDate;
import java.util.Set;

public record ChunkingContext(String documentId,String versionId,String title,FundDocumentType documentType,
        LocalDate publishedDate,Set<String> fundCodes,String sourceName,String sourceUri,String chunkingVersion) {}
