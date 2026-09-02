package com.jijing.fund.infrastructure.knowledge;

import com.jijing.fund.knowledge.domain.*;
import com.jijing.fund.knowledge.port.DocumentParser;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;

public final class PdfHtmlTextDocumentParser implements DocumentParser {
    @Override public ParsedDocument parse(String contentType,String fileName,byte[]content){try{return switch(contentType){case "application/pdf"->pdf(content);case "text/html"->html(content);case "text/plain"->text(content);default->throw new IllegalArgumentException("Unsupported contentType: "+contentType);};}catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalArgumentException("Document parsing failed",ex);}}
    private ParsedDocument pdf(byte[]content)throws Exception{List<ParsedPage>pages=new ArrayList<>();try(var document=Loader.loadPDF(content)){PDFTextStripper stripper=new PDFTextStripper();for(int page=1;page<=document.getNumberOfPages();page++){stripper.setStartPage(page);stripper.setEndPage(page);String text=clean(stripper.getText(document));pages.add(new ParsedPage(page,firstHeading(text),text));}}long searchable=pages.stream().filter(p->p.content().replaceAll("\\s+","").length()>=20).count();double ratio=pages.isEmpty()?0:(double)searchable/pages.size();List<String>warnings=ratio<.2?List.of("SCANNED_DOCUMENT_SUSPECTED: searchable-page-ratio="+String.format(Locale.ROOT,"%.2f",ratio)):List.of();return new ParsedDocument(pages,warnings);}
    private ParsedDocument html(byte[]content){var document=Jsoup.parse(new String(content,StandardCharsets.UTF_8));document.select("script,style,noscript,nav,footer,iframe,svg,[hidden],[aria-hidden=true]").remove();document.select("[style]").stream().filter(e->{String style=e.attr("style").replace(" ","").toLowerCase(Locale.ROOT);return style.contains("display:none")||style.contains("visibility:hidden");}).toList().forEach(org.jsoup.nodes.Element::remove);String heading=document.select("h1,h2,title").stream().findFirst().map(org.jsoup.nodes.Element::text).orElse("");return new ParsedDocument(List.of(new ParsedPage(1,heading,clean(document.body()==null?document.text():document.body().text()))),List.of());}
    private ParsedDocument text(byte[]content){String value=clean(new String(content,StandardCharsets.UTF_8));return new ParsedDocument(List.of(new ParsedPage(1,firstHeading(value),value)),List.of());}
    private String firstHeading(String text){if(text==null)return "";return text.lines().map(String::trim).filter(s->!s.isBlank()).filter(s->s.length()<=80).findFirst().orElse("");}
    private String clean(String value){return value==null?"":value.replace('\u0000',' ').replaceAll("[\\t\\x0B\\f\\r ]+"," ").replaceAll("\\n{3,}","\n\n").trim();}
}
