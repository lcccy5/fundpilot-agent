package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.ParsedDocument;
import com.jijing.fund.knowledge.domain.ParsedPage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按句末标点把解析结果切成带页码和来源的切片。
 * <p>
 * 目标长度、上限和重叠按「词元数 × 2」换成 UTF-16 字符数，不是分词器结果。
 * 句子在 {@code 。！？；!?;} 或换行处断开，标点留在前一句；中文逗号和英文句号不断句。
 * 空白页被跳过。规范化后仍短于最小字符数的文本不会成片，因此空文档、纯空白，
 * 以及整篇都达不到最小长度的文档返回空列表。完全相同的正文只保留第一次，重复句不占用片数上限。
 * 单句长于最大字符数时不在句内再切，该句会成为超长切片。
 * 某一页处理完后，去重后的草稿数超过上限时，抛出 {@link IllegalArgumentException}，消息为
 * {@code Document exceeds maximum chunk count}。
 * 页标题为 null 时写成空串；来源名称和 URI 原样拷自 {@link ChunkingContext}，
 * 可以为 null。本类不因缺少引用而丢弃切片。
 */
public final class ChineseDocumentChunker {
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？；!?;\\n])");
    private final int targetCharacters;
    private final int maxCharacters;
    private final int overlapCharacters;
    private final int minCharacters;
    private final int maxChunks;

    /**
     * @param targetTokens 达到该词元预算即尝试落成一片；必须为正
     * @param maxTokens 单片字符上限对应的词元数，必须不小于 {@code targetTokens}。超长单句仍可能超过换算后的字符上限
     * @param overlapTokens 前一片尾部保留的词元数；0 表示不重叠。换算后的字符数按 UTF-16 单元截取，不按码点
     * @param minCharacters 短于该长度的残留不成片；必须至少为 1
     * @param maxChunks 单页结束后允许保留的最大草稿数；必须至少为 1
     * @throws IllegalArgumentException 任一预算不合法，消息为 {@code Invalid chunk configuration}
     */
    public ChineseDocumentChunker(int targetTokens, int maxTokens, int overlapTokens, int minCharacters, int maxChunks) {
        if (targetTokens <= 0 || maxTokens < targetTokens || overlapTokens < 0 || minCharacters < 1 || maxChunks < 1) {
            throw new IllegalArgumentException("Invalid chunk configuration");
        }
        this.targetCharacters = targetTokens * 2;
        this.maxCharacters = maxTokens * 2;
        this.overlapCharacters = overlapTokens * 2;
        this.minCharacters = minCharacters;
        this.maxChunks = maxChunks;
    }

    /**
     * 把文档切成稳定标识的切片。标识由版本号、从 0 起的顺序和正文 SHA-256 决定，相同输入得到相同标识。
     * <p>
     * 传入 null 会抛出 {@link NullPointerException}，不视为空文档。
     * 页与页之间若残留短于最小长度，这段残留会原样接到下一页开头，而不是先截成重叠尾。
     * 全部页都没有形成草稿、但跨页残留非空白时，会再用空标题和页码 1 尝试落片；
     * 残留仍短于最小长度时结果依旧为空。
     * 缓冲区非空且再加一句就会超过最大字符数时，先落当前缓冲区，再把重叠尾放回缓冲区后整句追加。
     * 因此重叠尾可能单独成为一片，超长句本身不被拆开。
     */
    public List<DocumentChunk> split(ParsedDocument document, ChunkingContext context) {
        List<Draft> drafts = new ArrayList<>();
        String overlap = "";
        for (ParsedPage page : document.pages()) {
            String normalized = normalize(page.content());
            if (normalized.isBlank()) {
                continue;
            }
            String[] sentences = SENTENCE_BOUNDARY.split(normalized);
            StringBuilder buffer = new StringBuilder(overlap);
            int startPage = page.pageNumber();
            for (String sentence : sentences) {
                if (sentence.isBlank()) {
                    continue;
                }
                if (buffer.length() > 0 && buffer.length() + sentence.length() > maxCharacters) {
                    addDraft(drafts, buffer.toString(), page.heading(), startPage, page.pageNumber());
                    overlap = tail(buffer.toString());
                    buffer = new StringBuilder(overlap);
                    startPage = page.pageNumber();
                }
                buffer.append(sentence);
                if (buffer.length() >= targetCharacters) {
                    addDraft(drafts, buffer.toString(), page.heading(), startPage, page.pageNumber());
                    overlap = tail(buffer.toString());
                    buffer = new StringBuilder(overlap);
                    startPage = page.pageNumber();
                }
            }
            if (buffer.length() >= minCharacters) {
                addDraft(drafts, buffer.toString(), page.heading(), startPage, page.pageNumber());
                overlap = tail(buffer.toString());
            } else {
                overlap = buffer.toString();
            }
            if (drafts.size() > maxChunks) {
                throw new IllegalArgumentException("Document exceeds maximum chunk count");
            }
        }
        if (drafts.isEmpty() && !overlap.isBlank()) {
            addDraft(drafts, overlap, "", 1, 1);
        }
        List<DocumentChunk> result = new ArrayList<>();
        int order = 0;
        for (Draft draft : drafts) {
            String hash = KnowledgeHash.sha256(draft.content());
            String chunkId = KnowledgeHash.sha256(context.versionId() + ":" + order + ":" + hash).substring(0, 32);
            result.add(new DocumentChunk(
                    chunkId,
                    context.documentId(),
                    context.versionId(),
                    draft.content(),
                    draft.heading(),
                    draft.pageStart(),
                    draft.pageEnd(),
                    order,
                    estimateTokens(draft.content()),
                    context.chunkingVersion(),
                    hash,
                    context.fundCodes(),
                    context.documentType(),
                    context.title(),
                    context.publishedDate(),
                    context.sourceName(),
                    context.sourceUri()));
            order++;
        }
        return List.copyOf(result);
    }

    /**
     * 规范化后达到最小长度、且正文尚未出现过时才追加草稿。标题为 null 时写成空串。
     */
    private void addDraft(List<Draft> drafts, String content, String heading, int start, int end) {
        String clean = normalize(content);
        if (clean.length() >= minCharacters && !drafts.stream().anyMatch(draft -> draft.content().equals(clean))) {
            drafts.add(new Draft(clean, heading == null ? "" : heading, start, end));
        }
    }

    /**
     * 重叠为 0 时返回空串，否则取末尾若干 UTF-16 字符。短于重叠长度时返回整段。
     */
    private String tail(String value) {
        return overlapCharacters == 0 ? "" : value.substring(Math.max(0, value.length() - overlapCharacters));
    }

    /**
     * null 视为空串。NUL 换成空格，制表符、垂直制表、换页、回车和空格压成一个空格，
     * 连续三个及以上换行压成两个，再去掉首尾空白。
     */
    private String normalize(String value) {
        return value == null
                ? ""
                : value.replace('\u0000', ' ')
                        .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                        .replaceAll("\\n{3,}", "\n\n")
                        .trim();
    }

    /**
     * 用码点数估算词元：加一后除以二，至少为 1。与按 UTF-16 长度截取的重叠不是同一把尺子。
     */
    private int estimateTokens(String value) {
        return Math.max(1, (value.codePointCount(0, value.length()) + 1) / 2);
    }

    /**
     * 尚未分配切片标识的正文草稿。页码和标题在生成 {@link DocumentChunk} 时原样拷贝。
     */
    private record Draft(String content, String heading, int pageStart, int pageEnd) {}
}
