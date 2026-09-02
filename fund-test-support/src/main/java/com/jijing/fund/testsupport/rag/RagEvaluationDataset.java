package com.jijing.fund.testsupport.rag;
import com.fasterxml.jackson.databind.ObjectMapper;import java.io.*;import java.nio.file.*;import java.util.*;
public final class RagEvaluationDataset {
 private final ObjectMapper mapper;public RagEvaluationDataset(ObjectMapper mapper){this.mapper=mapper;}
 public List<RagEvaluationCase>load(Path path,int minimumCases){try(var lines=Files.lines(path)){List<RagEvaluationCase>cases=lines.map(String::trim).filter(v->!v.isEmpty()&&!v.startsWith("#")).map(this::parse).toList();if(cases.size()<minimumCases)throw new IllegalArgumentException("RAG dataset requires at least "+minimumCases+" cases but has "+cases.size());long distinct=cases.stream().map(RagEvaluationCase::id).distinct().count();if(distinct!=cases.size())throw new IllegalArgumentException("RAG dataset contains duplicate ids");return cases;}catch(IOException e){throw new IllegalStateException("Cannot read RAG evaluation dataset",e);}}
 private RagEvaluationCase parse(String value){try{return mapper.readValue(value,RagEvaluationCase.class);}catch(Exception e){throw new IllegalArgumentException("Invalid RAG evaluation JSONL row",e);}}
}
