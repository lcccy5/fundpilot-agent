package com.jijing.fund.agent.evals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.orchestration.FundAgentSafetyPolicy;
import com.jijing.fund.agent.planning.PlanDraft;
import com.jijing.fund.agent.planning.PlanTaskDraft;
import com.jijing.fund.agent.planning.PlanValidationException;
import com.jijing.fund.agent.planning.PlanValidator;
import com.jijing.fund.agent.tool.FundComparisonTool;
import com.jijing.fund.agent.tool.FundDocumentSearchTool;
import com.jijing.fund.agent.tool.FundMetricsTool;
import com.jijing.fund.agent.tool.FundNavTool;
import com.jijing.fund.agent.tool.FundProfileTool;
import com.jijing.fund.agent.tool.FundToolRouter;
import com.jijing.fund.agent.tool.PersonalFundTool;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class PersonalizationEvalV3Test {
    private final ObjectMapper mapper=new ObjectMapper();
    private final FundAgentSafetyPolicy policy=new FundAgentSafetyPolicy();
    private final PlanValidator validator=new PlanValidator();

    @Test void personalizationRoutingLoadsPersonalToolsOnlyForMineIntents()throws Exception{
        var personal=mock(PersonalFundTool.class);
        var router=new FundToolRouter(mock(FundProfileTool.class),mock(FundNavTool.class),mock(FundMetricsTool.class),mock(FundComparisonTool.class),mock(FundDocumentSearchTool.class),null,null,null,personal);
        for(JsonNode row:load("evals/personalization-routing-v3.jsonl")){
            boolean hasPersonal=java.util.Arrays.asList(router.toolsFor(row.get("question").asText())).contains(personal);
            assertThat(hasPersonal).as(row.get("id").asText()).isEqualTo(row.get("expectPersonal").asBoolean());
        }
    }

    @Test void ownershipAndTradeInjectionAreDenied()throws Exception{
        for(JsonNode row:load("evals/ownership-safety-v3.jsonl")){
            String deny=row.get("deny").asText();
            if("input".equals(deny))assertThatThrownBy(()->policy.validateInput(row.get("question").asText())).as(row.get("id").asText()).isInstanceOf(com.jijing.fund.agent.exception.AgentPolicyViolationException.class);
            if("plan".equals(deny)){
                var leak=new PlanDraft("g",Map.of(),Map.of(),List.of(new PlanTaskDraft("a","PORTFOLIO_SNAPSHOT",Map.of("userId",row.get("taskUserId").asText()),List.of(),List.of("PORTFOLIO"))));
                assertThatThrownBy(()->validator.validate(leak,row.get("owner").asText())).isInstanceOf(PlanValidationException.class);
            }
        }
    }

    @Test void suitabilityAnswersRejectGuaranteeAndAllIn()throws Exception{
        for(JsonNode row:load("evals/suitability-safety-v3.jsonl")){
            if("output".equals(row.get("deny").asText()))assertThatThrownBy(()->policy.validateAnswer(row.get("answer").asText())).as(row.get("id").asText()).isInstanceOf(com.jijing.fund.agent.exception.AgentPolicyViolationException.class);
            else policy.validateAnswer(row.get("answer").asText());
        }
    }

    @Test void portfolioEvidenceDatasetDocumentsUnavailableAndCoverageContracts()throws Exception{
        var rows=load("evals/portfolio-evidence-v3.jsonl");
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(n->n.get("expect").asText()).toList()).contains("UNAVAILABLE","rebuild","coverage");
    }

    private List<JsonNode> load(String resource)throws Exception{
        List<JsonNode> rows=new ArrayList<>();
        try(var stream=getClass().getClassLoader().getResourceAsStream(resource);var reader=new BufferedReader(new InputStreamReader(Objects.requireNonNull(stream),StandardCharsets.UTF_8))){
            String line;while((line=reader.readLine())!=null)if(!line.isBlank())rows.add(mapper.readTree(line));
        }
        return rows;
    }
}
