package com.jijing.fund.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.domain.*;
import jakarta.validation.Validation;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FundDocumentSearchToolTest {
    @Test void returnsPageAwareDocumentEvidence(){KnowledgeSearchUseCase useCase=mock(KnowledgeSearchUseCase.class);AgentRuntimeRepository audit=mock(AgentRuntimeRepository.class);var chunk=new DocumentChunk("chunk-1","doc-1","ver-1","基金经理表示将控制组合波动。","投资策略",8,8,0,20,"chunk-v1","hash",Set.of("000001"),FundDocumentType.QUARTERLY_REPORT,"2026二季度报告",LocalDate.of(2026,6,30),"manual","https://example.test/q.pdf");when(useCase.search(any())).thenReturn(new KnowledgeSearchResult("retrieval-1",List.of(new RetrievedChunk(chunk,0.1,1,Set.of("bm25","vector"))),List.of()));var trace=new AgentExecutionTrace("run-1",audit,new ObjectMapper(),6,1,Duration.ofSeconds(1));var tool=new FundDocumentSearchTool(useCase,Validation.buildDefaultValidatorFactory().getValidator());var result=tool.search(new FundDocumentSearchTool.SearchInput(List.of("000001"),"基金经理如何解释波动",Set.of(FundDocumentType.QUARTERLY_REPORT),null,null,5),new ToolContext(Map.of(AgentExecutionTrace.TOOL_CONTEXT_KEY,trace)));assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);assertThat(result.evidence()).singleElement().satisfies(e->{assertThat(e.evidenceId()).isEqualTo("DOC:doc-1:ver-1:chunk-1");assertThat(e.pageStart()).isEqualTo(8);});verify(audit).recordToolCall(any());}
}
