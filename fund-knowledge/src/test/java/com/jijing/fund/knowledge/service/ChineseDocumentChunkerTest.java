package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.ParsedDocument;
import com.jijing.fund.knowledge.domain.ParsedPage;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认正常中文段落会切出多片，并且页码、标题、基金代码和切片标识保持稳定。
 */
class ChineseDocumentChunkerTest {
    /**
     * 同一篇季报连续切两次，标识、页码和基金代码都不变。
     */
    @Test
    void preservesPageAndMetadataAndProducesStableIds() {
        String paragraph = "本基金坚持长期投资，并根据市场变化控制组合风险。".repeat(20);
        ParsedDocument parsed = new ParsedDocument(List.of(new ParsedPage(7, "投资策略", paragraph)), List.of());
        ChunkingContext context = new ChunkingContext(
                "doc-1",
                "ver-1",
                "二季度报告",
                FundDocumentType.QUARTERLY_REPORT,
                LocalDate.of(2026, 6, 30),
                Set.of("000001"),
                "upload",
                "https://example.test/doc.pdf",
                "chunk-v1");
        ChineseDocumentChunker chunker = new ChineseDocumentChunker(80, 120, 10, 30, 100);
        List<DocumentChunk> first = chunker.split(parsed, context);
        List<DocumentChunk> second = chunker.split(parsed, context);
        assertThat(first).hasSizeGreaterThan(1);
        assertThat(first).extracting(DocumentChunk::chunkId)
                .containsExactlyElementsOf(second.stream().map(DocumentChunk::chunkId).toList());
        assertThat(first).allSatisfy(chunk -> {
            assertThat(chunk.pageStart()).isEqualTo(7);
            assertThat(chunk.headingPath()).isEqualTo("投资策略");
            assertThat(chunk.fundCodes()).containsExactly("000001");
        });
    }
}
