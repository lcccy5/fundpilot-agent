package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.domain.*;
import java.util.*;
import java.util.regex.Pattern;

public final class ChineseDocumentChunker {
    private static final Pattern SENTENCE_BOUNDARY=Pattern.compile("(?<=[。！？；!?;\\n])");
    private final int targetCharacters,maxCharacters,overlapCharacters,minCharacters,maxChunks;

    public ChineseDocumentChunker(int targetTokens,int maxTokens,int overlapTokens,int minCharacters,int maxChunks){
        if(targetTokens<=0||maxTokens<targetTokens||overlapTokens<0||minCharacters<1||maxChunks<1)throw new IllegalArgumentException("Invalid chunk configuration");
        this.targetCharacters=targetTokens*2;this.maxCharacters=maxTokens*2;this.overlapCharacters=overlapTokens*2;this.minCharacters=minCharacters;this.maxChunks=maxChunks;
    }

    public List<DocumentChunk> split(ParsedDocument document,ChunkingContext context){
        List<Draft> drafts=new ArrayList<>();String overlap="";
        for(ParsedPage page:document.pages()){
            String normalized=normalize(page.content());if(normalized.isBlank())continue;
            String[] sentences=SENTENCE_BOUNDARY.split(normalized);StringBuilder buffer=new StringBuilder(overlap);int startPage=page.pageNumber();
            for(String sentence:sentences){if(sentence.isBlank())continue;
                if(buffer.length()>0&&buffer.length()+sentence.length()>maxCharacters){addDraft(drafts,buffer.toString(),page.heading(),startPage,page.pageNumber());overlap=tail(buffer.toString());buffer=new StringBuilder(overlap);startPage=page.pageNumber();}
                buffer.append(sentence);
                if(buffer.length()>=targetCharacters){addDraft(drafts,buffer.toString(),page.heading(),startPage,page.pageNumber());overlap=tail(buffer.toString());buffer=new StringBuilder(overlap);startPage=page.pageNumber();}
            }
            if(buffer.length()>=minCharacters){addDraft(drafts,buffer.toString(),page.heading(),startPage,page.pageNumber());overlap=tail(buffer.toString());}
            else overlap=buffer.toString();
            if(drafts.size()>maxChunks)throw new IllegalArgumentException("Document exceeds maximum chunk count");
        }
        if(drafts.isEmpty()&&!overlap.isBlank())addDraft(drafts,overlap,"",1,1);
        List<DocumentChunk> result=new ArrayList<>();int order=0;
        for(Draft draft:drafts){String hash=KnowledgeHash.sha256(draft.content());String chunkId=KnowledgeHash.sha256(context.versionId()+":"+order+":"+hash).substring(0,32);
            result.add(new DocumentChunk(chunkId,context.documentId(),context.versionId(),draft.content(),draft.heading(),draft.pageStart(),draft.pageEnd(),order,estimateTokens(draft.content()),context.chunkingVersion(),hash,context.fundCodes(),context.documentType(),context.title(),context.publishedDate(),context.sourceName(),context.sourceUri()));order++;}
        return List.copyOf(result);
    }
    private void addDraft(List<Draft> drafts,String content,String heading,int start,int end){String clean=normalize(content);if(clean.length()>=minCharacters&&!drafts.stream().anyMatch(d->d.content().equals(clean)))drafts.add(new Draft(clean,heading==null?"":heading,start,end));}
    private String tail(String value){return overlapCharacters==0?"":value.substring(Math.max(0,value.length()-overlapCharacters));}
    private String normalize(String value){return value==null?"":value.replace('\u0000',' ').replaceAll("[\\t\\x0B\\f\\r ]+"," ").replaceAll("\\n{3,}","\n\n").trim();}
    private int estimateTokens(String value){return Math.max(1,(value.codePointCount(0,value.length())+1)/2);}
    private record Draft(String content,String heading,int pageStart,int pageEnd){}
}
