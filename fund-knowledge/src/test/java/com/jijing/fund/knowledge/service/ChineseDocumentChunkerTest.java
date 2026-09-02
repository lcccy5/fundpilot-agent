package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ChineseDocumentChunkerTest {
    @Test void preservesPageAndMetadataAndProducesStableIds(){
        String paragraph="本基金坚持长期投资，并根据市场变化控制组合风险。".repeat(20);
        var parsed=new ParsedDocument(List.of(new ParsedPage(7,"投资策略",paragraph)),List.of());
        var context=new ChunkingContext("doc-1","ver-1","二季度报告",FundDocumentType.QUARTERLY_REPORT,LocalDate.of(2026,6,30),Set.of("000001"),"upload","https://example.test/doc.pdf","chunk-v1");
        var chunker=new ChineseDocumentChunker(80,120,10,30,100);
        var first=chunker.split(parsed,context);var second=chunker.split(parsed,context);
        assertThat(first).hasSizeGreaterThan(1);assertThat(first).extracting(DocumentChunk::chunkId).containsExactlyElementsOf(second.stream().map(DocumentChunk::chunkId).toList());
        assertThat(first).allSatisfy(chunk->{assertThat(chunk.pageStart()).isEqualTo(7);assertThat(chunk.headingPath()).isEqualTo("投资策略");assertThat(chunk.fundCodes()).containsExactly("000001");});
    }
}
