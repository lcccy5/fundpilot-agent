package com.jijing.fund.testsupport.rag;
import com.fasterxml.jackson.databind.ObjectMapper;import java.nio.file.*;
public final class RagEvaluationReportWriter {
 private final ObjectMapper mapper;public RagEvaluationReportWriter(ObjectMapper mapper){this.mapper=mapper;}
 public void write(Path target,RagEvaluationRunner.Report report){try{Path absolute=target.toAbsolutePath().normalize();if(absolute.getParent()!=null)Files.createDirectories(absolute.getParent());Path temp=Files.createTempFile(absolute.getParent(),absolute.getFileName().toString(),".tmp");mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),report);try{Files.move(temp,absolute,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,absolute,StandardCopyOption.REPLACE_EXISTING);}}catch(Exception e){throw new IllegalStateException("Cannot write RAG evaluation report",e);}}
}
