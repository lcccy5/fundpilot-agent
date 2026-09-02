package com.jijing.fund.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FundToolRouterEvaluationTest {
    @Test void fixedFiftyCaseDatasetMeetsRoutingGate()throws Exception{var router=new FundToolRouter(mock(FundProfileTool.class),mock(FundNavTool.class),mock(FundMetricsTool.class),mock(FundComparisonTool.class),mock(FundDocumentSearchTool.class));ObjectMapper mapper=new ObjectMapper();List<Case>cases=new ArrayList<>();try(var stream=getClass().getResourceAsStream("/evals/fund-agent-v1.jsonl");var reader=new BufferedReader(new InputStreamReader(Objects.requireNonNull(stream),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)if(!line.isBlank())cases.add(mapper.readValue(line,Case.class));}long correct=cases.stream().filter(c->toolNames(router.toolsFor(c.question())).equals(c.expected())).count();assertThat(cases).hasSize(50);assertThat((double)correct/cases.size()).isGreaterThanOrEqualTo(0.90);}
    private List<String>toolNames(Object[]tools){return Arrays.stream(tools).map(t->{if(t instanceof FundProfileTool)return FundProfileTool.NAME;if(t instanceof FundNavTool)return FundNavTool.NAME;if(t instanceof FundMetricsTool)return FundMetricsTool.NAME;if(t instanceof FundComparisonTool)return FundComparisonTool.NAME;if(t instanceof FundDocumentSearchTool)return FundDocumentSearchTool.NAME;throw new IllegalArgumentException();}).toList();}
    @Test void realtimeQuestionUsesRealtimeQuoteInsteadOfHistoricalMetrics(){var profile=mock(FundProfileTool.class);var realtime=mock(FundRealtimeQuoteTool.class);var router=new FundToolRouter(profile,mock(FundNavTool.class),mock(FundMetricsTool.class),mock(FundComparisonTool.class),mock(FundDocumentSearchTool.class),realtime);assertThat(router.toolsFor("004069 今天实时涨跌幅")).containsExactly(realtime,profile);}
    @Test void sectorForecastUsesScenarioToolWithoutRequiringFundCode(){var sector=mock(SectorOutlookTool.class);var router=new FundToolRouter(mock(FundProfileTool.class),mock(FundNavTool.class),mock(FundMetricsTool.class),mock(FundComparisonTool.class),mock(FundDocumentSearchTool.class),mock(FundRealtimeQuoteTool.class),sector);assertThat(router.toolsFor("帮我预测一下机器人板块未来一个月的情况")).containsExactly(sector);}
    @Test void naturalSectorForecastWithoutSectorSuffixUsesScenarioTool(){var sector=mock(SectorOutlookTool.class);var router=new FundToolRouter(mock(FundProfileTool.class),mock(FundNavTool.class),mock(FundMetricsTool.class),mock(FundComparisonTool.class),mock(FundDocumentSearchTool.class),mock(FundRealtimeQuoteTool.class),sector);assertThat(router.toolsFor("分析白酒未来一个月趋势")).containsExactly(sector);}
    @Test void catalystQuestionUsesOnlyCompleteCatalystResearchChain(){var sector=mock(SectorOutlookTool.class);var catalyst=mock(FundCatalystResearchTool.class);var router=new FundToolRouter(mock(FundProfileTool.class),mock(FundNavTool.class),mock(FundMetricsTool.class),mock(FundComparisonTool.class),mock(FundDocumentSearchTool.class),mock(FundRealtimeQuoteTool.class),sector,catalyst);assertThat(router.toolsFor("机器人板块未来可能有哪些真实利好利空")).containsExactly(catalyst);}
    private record Case(String id,String question,List<String>expected){public Case{expected=List.copyOf(expected);}}
}
