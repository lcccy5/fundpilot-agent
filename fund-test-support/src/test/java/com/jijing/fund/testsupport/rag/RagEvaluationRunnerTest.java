package com.jijing.fund.testsupport.rag;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class RagEvaluationRunnerTest {
 @Test void enforcesDeterministicQualityGates(){var c=new RagEvaluationCase("rag-1","问题",Set.of("000001"),Set.of("QUARTERLY_REPORT"),Set.of("chunk-1"),Set.of("DOC:1"),List.of("要点"),List.of("保证上涨"),false);var observed=new RagEvaluationRunner.ObservedCase(c,List.of("chunk-1"),Set.of("DOC:1"),List.of("DOC:1"),"合规回答",false);var report=new RagEvaluationRunner().run("rag-v1",List.of(observed));assertThat(report.passed()).isTrue();assertThat(report.scores().get("recallAt10")).isEqualTo(1);}
}
