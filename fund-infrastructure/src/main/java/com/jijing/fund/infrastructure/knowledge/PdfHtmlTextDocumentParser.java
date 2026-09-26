package com.jijing.fund.infrastructure.knowledge;

import com.jijing.fund.knowledge.domain.ParsedDocument;
import com.jijing.fund.knowledge.domain.ParsedPage;
import com.jijing.fund.knowledge.port.DocumentParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * 解析 PDF、HTML 和纯文本。不访问网络，没有超时或连接失败。
 * 不支持的类型、PDF 读失败会抛出 {@link IllegalArgumentException}；解析器自身的运行时异常原样抛出。
 * 空字节在清洗后变成空页，不单独报空载荷。没有重复提交概念。
 */
public final class PdfHtmlTextDocumentParser implements DocumentParser {
    /**
     * PDF 按页抽取；HTML 去掉脚本和隐藏节点后合成一页；纯文本也合成一页。
     */
    @Override
    public ParsedDocument parse(String contentType, String fileName, byte[] content) {
        try {
            return switch (contentType) {
                case "application/pdf" -> pdf(content);
                case "text/html" -> html(content);
                case "text/plain" -> text(content);
                default -> throw new IllegalArgumentException("Unsupported contentType: " + contentType);
            };
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Document parsing failed", ex);
        }
    }

    /**
     * 可搜索页（去掉空白后至少 20 字）占比低于 0.2 时给出扫描件警告，仍返回已抽出的文本。
     */
    private ParsedDocument pdf(byte[] content) throws Exception {
        List<ParsedPage> pages = new ArrayList<>();
        try (var document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = clean(stripper.getText(document));
                pages.add(new ParsedPage(page, firstHeading(pageText), pageText));
            }
        }
        long searchable = pages.stream().filter(page -> page.content().replaceAll("\\s+", "").length() >= 20).count();
        double ratio = pages.isEmpty() ? 0 : (double) searchable / pages.size();
        List<String> warnings = ratio < .2
                ? List.of("SCANNED_DOCUMENT_SUSPECTED: searchable-page-ratio="
                        + String.format(Locale.ROOT, "%.2f", ratio))
                : List.of();
        return new ParsedDocument(pages, warnings);
    }

    /** 隐藏、脚本和导航节点删除后再取正文，避免把样式或提示词送进索引。 */
    private ParsedDocument html(byte[] content) {
        var document = Jsoup.parse(new String(content, StandardCharsets.UTF_8));
        document.select("script,style,noscript,nav,footer,iframe,svg,[hidden],[aria-hidden=true]").remove();
        document.select("[style]").stream().filter(element -> {
            String style = element.attr("style").replace(" ", "").toLowerCase(Locale.ROOT);
            return style.contains("display:none") || style.contains("visibility:hidden");
        }).toList().forEach(Element::remove);
        String heading = document.select("h1,h2,title").stream().findFirst().map(Element::text).orElse("");
        String body = document.body() == null ? document.text() : document.body().text();
        return new ParsedDocument(List.of(new ParsedPage(1, heading, clean(body))), List.of());
    }

    /** 纯文本没有结构，标题取清洗后的第一行短句。 */
    private ParsedDocument text(byte[] content) {
        String value = clean(new String(content, StandardCharsets.UTF_8));
        return new ParsedDocument(List.of(new ParsedPage(1, firstHeading(value), value)), List.of());
    }

    /** 没有合适短行时标题为空字符串，不返回 null。 */
    private String firstHeading(String text) {
        if (text == null) {
            return "";
        }
        return text.lines().map(String::trim).filter(line -> !line.isBlank()).filter(line -> line.length() <= 80)
                .findFirst().orElse("");
    }

    /** 去掉 NUL 和多余空白，避免切块时把排版噪声当成正文。 */
    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u0000', ' ').replaceAll("[\\t\\x0B\\f\\r ]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
