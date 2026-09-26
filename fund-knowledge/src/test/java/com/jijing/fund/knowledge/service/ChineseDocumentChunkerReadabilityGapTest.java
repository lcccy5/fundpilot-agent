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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 锁定切块在空文档、超长中文、重复正文和缺失引用上的现有行为。
 */
class ChineseDocumentChunkerReadabilityGapTest {
    /**
     * 没有页、只有空白，以及短于最小长度的残留，都不会产生切片。
     */
    @Test
    void emptyOrTooShortDocumentsProduceNoChunks() {
        ChineseDocumentChunker chunker = new ChineseDocumentChunker(40, 80, 0, 30, 10);
        ChunkingContext context = context("来源", "https://example.test/a");
        assertThat(chunker.split(new ParsedDocument(List.of(), List.of()), context)).isEmpty();
        assertThat(chunker.split(new ParsedDocument(List.of(new ParsedPage(1, "标题", " \n\t")), List.of()), context)).isEmpty();
        assertThat(chunker.split(new ParsedDocument(List.of(new ParsedPage(2, "标题", "短。")), List.of()), context)).isEmpty();
        assertThat(chunker.split(
                new ParsedDocument(List.of(new ParsedPage(2, "标题", "短。"), new ParsedPage(3, "标题", "   ")), List.of()),
                context)).isEmpty();
    }

    /**
     * 没有句内标点的超长中文保持整句，即使已经超过最大字符数。
     */
    @Test
    void overlongChineseSentenceStaysOneChunk() {
        String sentence = "甲".repeat(40) + "。";
        ChineseDocumentChunker chunker = new ChineseDocumentChunker(4, 6, 0, 2, 10);
        List<DocumentChunk> chunks = chunker.split(
                new ParsedDocument(List.of(new ParsedPage(4, "投资策略", sentence)), List.of()),
                context("来源", "https://example.test/a"));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).isEqualTo(sentence);
        assertThat(chunks.getFirst().content().length()).isGreaterThan(12);
    }

    /**
     * 重叠尾巴在下一句放不下时会单独成片，超长句本身仍然不被拆开。
     */
    @Test
    void overlapTailBecomesItsOwnChunkWhenTheNextSentenceDoesNotFit() {
        String first = "甲".repeat(20) + "。";
        String second = "乙".repeat(20) + "。";
        ChineseDocumentChunker chunker = new ChineseDocumentChunker(4, 6, 2, 2, 20);
        List<DocumentChunk> chunks = chunker.split(
                new ParsedDocument(List.of(new ParsedPage(1, "标题", first + second)), List.of()),
                context("来源", null));
        assertThat(chunks).extracting(DocumentChunk::content).containsExactly(
                first,
                "甲甲甲。",
                "甲甲甲。" + second,
                "乙乙乙。");
    }

    /**
     * 规范化后正文完全相同的页只保留第一次。
     */
    @Test
    void duplicateNormalizedTextIsKeptOnce() {
        String text = "这是一段说明。";
        ChineseDocumentChunker chunker = new ChineseDocumentChunker(50, 80, 0, 2, 10);
        List<DocumentChunk> chunks = chunker.split(
                new ParsedDocument(List.of(
                        new ParsedPage(1, "甲", text),
                        new ParsedPage(2, "乙", text + "   ")), List.of()),
                context("来源", "https://example.test/a"));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).isEqualTo(text);
        assertThat(chunks.getFirst().headingPath()).isEqualTo("甲");
    }

    /**
     * 重复句只留一片，因此不会把文档推过片数上限。
     */
    @Test
    void identicalSentencesDoNotConsumeTheChunkCap() {
        ChineseDocumentChunker chunker = new ChineseDocumentChunker(5, 20, 0, 1, 1);
        List<DocumentChunk> chunks = chunker.split(
                new ParsedDocument(List.of(new ParsedPage(1, "标题", "一二三四五六七八九。".repeat(2))), List.of()),
                context("来源", null));
        assertThat(chunks).hasSize(1);
    }

    /**
     * 单页去重后的草稿数超过上限时拒绝整篇，而不是悄悄截断。
     */
    @Test
    void tooManyChunksAreRejected() {
        ChineseDocumentChunker chunker = new ChineseDocumentChunker(5, 20, 0, 1, 1);
        assertThatThrownBy(() -> chunker.split(
                new ParsedDocument(List.of(new ParsedPage(1, "标题", "一二三四五六七八九。甲乙丙丁戊己庚辛壬。")), List.of()),
                context("来源", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Document exceeds maximum chunk count");
    }

    /**
     * 词元预算、重叠、最小长度或片数上限不合法时拒绝构造。
     */
    @Test
    void invalidBudgetIsRejected() {
        assertThatThrownBy(() -> new ChineseDocumentChunker(0, 10, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid chunk configuration");
        assertThatThrownBy(() -> new ChineseDocumentChunker(8, 4, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChineseDocumentChunker(4, 8, -1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChineseDocumentChunker(4, 8, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChineseDocumentChunker(4, 8, 0, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 标题和来源缺失时仍然成片：标题写成空串，来源保持 null，基金代码收成空集。
     */
    @Test
    void missingCitationFieldsAreStillChunked() {
        ChineseDocumentChunker chunker = new ChineseDocumentChunker(50, 80, 0, 2, 10);
        ChunkingContext context = new ChunkingContext(
                "doc-1", "ver-1", null, FundDocumentType.OTHER, null, null, null, null, "chunk-v1");
        List<DocumentChunk> chunks = chunker.split(
                new ParsedDocument(List.of(new ParsedPage(3, null, "这是一段说明。")), List.of()),
                context);
        assertThat(chunks).hasSize(1);
        DocumentChunk chunk = chunks.getFirst();
        assertThat(chunk.headingPath()).isEmpty();
        assertThat(chunk.sourceName()).isNull();
        assertThat(chunk.sourceUri()).isNull();
        assertThat(chunk.documentTitle()).isNull();
        assertThat(chunk.publishedDate()).isNull();
        assertThat(chunk.fundCodes()).isEmpty();
        assertThat(chunk.pageStart()).isEqualTo(3);
    }

    /**
     * 组装带固定文档身份的切块上下文。来源可以故意为空，用来观察引用是否被丢掉。
     */
    private static ChunkingContext context(String sourceName, String sourceUri) {
        return new ChunkingContext(
                "doc-1",
                "ver-1",
                "季报",
                FundDocumentType.QUARTERLY_REPORT,
                LocalDate.of(2026, 6, 30),
                Set.of("000001"),
                sourceName,
                sourceUri,
                "chunk-v1");
    }
}
