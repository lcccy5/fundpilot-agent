package com.jijing.fund.agent.orchestration;

import com.jijing.fund.agent.routing.ExecutionModeRouter;
import com.jijing.fund.agent.capability.*;
import com.jijing.fund.agent.tool.*;
import com.jijing.fund.application.*;
import com.jijing.fund.application.research.RealtimeFundQuoteUseCase;
import com.jijing.fund.application.watchlist.WatchlistUseCase;
import com.jijing.fund.application.portfolio.PortfolioUseCase;
import com.jijing.fund.domain.identity.UserAccountRepository;
import com.jijing.fund.knowledge.api.KnowledgeSearchUseCase;
import jakarta.validation.Validator;
import org.springframework.ai.chat.memory.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StreamUtils;
import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(FundAgentProperties.class)
/** 实现 FundAgentConfiguration 所代表的 Agent 运行时职责。 */
public class FundAgentConfiguration {
    @Bean @ConditionalOnProperty(prefix="fund.agent",name="enabled",havingValue="true")
    FundAgentPrompt fundAgentPrompt(ResourceLoader resources,FundAgentProperties properties) throws java.io.IOException {
        var resource=resources.getResource("classpath:prompts/"+properties.promptVersion()+".txt");
        if(!resource.exists())throw new IllegalStateException("Missing agent prompt resource: "+properties.promptVersion());
        String content=StreamUtils.copyToString(resource.getInputStream(),StandardCharsets.UTF_8);
        return new FundAgentPrompt(properties.promptVersion(),content,AgentExecutionTrace.sha256(content));
    }
    /** Keeps the original classpath prompt path when AgentOps prompt resolution is disabled. */
    @Bean @ConditionalOnMissingBean(FundAgentPromptResolver.class)
    FundAgentPromptResolver localFundAgentPromptResolver(FundAgentPrompt prompt){return request->ResolvedFundAgentPrompt.local(prompt);}
    @Bean AgentModelDescriptor agentModelDescriptor(Environment environment){
        return new AgentModelDescriptor(environment.getProperty("spring.ai.model.chat","unknown"),
                environment.getProperty("spring.ai.openai.chat.options.model","configured"));
    }
    @Bean ExecutionModeRouter executionModeRouter(){return new ExecutionModeRouter();}
    @Bean CapabilityExecutorRegistry capabilityExecutorRegistry(java.util.List<AgentCapabilityExecutor> executors){return new CapabilityExecutorRegistry(executors);}
    /** Supplies an in-memory store only when the infrastructure JDBC adapter is not part of the current application. */
    @Bean @ConditionalOnMissingBean(com.jijing.fund.agent.graph.GraphCheckpointStore.class)
    com.jijing.fund.agent.graph.GraphCheckpointStore graphCheckpointStore(){return new com.jijing.fund.agent.graph.InMemoryGraphCheckpointStore();}
    @Bean AgentCapabilityExecutor fundMetricsCapabilityExecutor(FundMetricsQueryUseCase useCase,com.fasterxml.jackson.databind.ObjectMapper mapper){return new FundMetricsCapabilityExecutor(useCase,mapper);}
    @Bean AgentCapabilityExecutor fundComparisonCapabilityExecutor(FundComparisonUseCase useCase,com.fasterxml.jackson.databind.ObjectMapper mapper){return new FundComparisonCapabilityExecutor(useCase,mapper);}
    @Bean AgentCapabilityExecutor reportVerifyCapabilityExecutor(com.jijing.fund.agent.port.AgentDagRepository dag){return new ReportVerifyCapabilityExecutor(dag);}
    @Bean AgentCapabilityExecutor reportWriteCapabilityExecutor(com.jijing.fund.agent.port.AgentDagRepository dag){return new ReportWriteCapabilityExecutor(dag);}
    @Bean AgentCapabilityExecutor portfolioSnapshotCapabilityExecutor(PortfolioUseCase useCase,UserAccountRepository accounts,com.fasterxml.jackson.databind.ObjectMapper mapper,java.time.Clock clock){return new PortfolioSnapshotCapabilityExecutor(useCase,accounts,mapper,clock);}
    @Bean AgentCapabilityExecutor reportExportCapabilityExecutor(com.jijing.fund.agent.port.AgentDagRepository dag,com.fasterxml.jackson.databind.ObjectMapper mapper){return new ReportExportCapabilityExecutor(dag,mapper);}
    /** Registers the LangGraph4j catalyst subgraph behind the server-controlled capability registry. */
    @Bean AgentCapabilityExecutor catalystResearchCapabilityExecutor(FundCatalystResearchTool tool,com.jijing.fund.agent.port.AgentDagRepository dag,com.jijing.fund.agent.graph.GraphCheckpointStore checkpoints,com.fasterxml.jackson.databind.ObjectMapper mapper){return new CatalystResearchCapabilityExecutor(tool,dag,checkpoints,mapper);}
    @Bean com.jijing.fund.agent.execution.PlanTaskWorker planTaskWorker(com.jijing.fund.agent.port.AgentDagRepository dag,CapabilityExecutorRegistry executors){return new com.jijing.fund.agent.execution.PlanTaskWorker(dag,executors);}
    @Bean com.jijing.fund.agent.execution.AgentCoordinator agentCoordinator(ExecutionModeRouter router,com.jijing.fund.agent.port.AgentDagRepository dag,com.jijing.fund.agent.execution.PlanTaskWorker worker,java.time.Clock clock){return new com.jijing.fund.agent.execution.AgentCoordinator(router,dag,worker,new com.jijing.fund.agent.planning.RuleBasedPlanner(clock));}
    @Bean com.jijing.fund.agent.notification.NotificationDispatcher notificationDispatcher(com.jijing.fund.agent.notification.NotificationStore store){
        return new com.jijing.fund.agent.notification.NotificationDispatcher(store,new com.jijing.fund.agent.notification.InAppNotificationChannel());
    }
    @Bean com.jijing.fund.agent.event.DomainEventDispatcher domainEventDispatcher(com.jijing.fund.agent.notification.NotificationDispatcher notifications){
        return new com.jijing.fund.agent.event.DomainEventDispatcher(notifications);
    }
    @Bean com.jijing.fund.agent.report.MonthlyReportLauncher monthlyReportLauncher(com.jijing.fund.agent.api.AgentRunUseCase runs,com.jijing.fund.agent.report.ReportJobStore jobs,java.time.Clock clock){
        return new com.jijing.fund.agent.report.MonthlyReportLauncher(runs,jobs,clock);
    }
    @Bean com.jijing.fund.agent.mcp.FundMcpServer fundMcpServer(com.jijing.fund.agent.mcp.McpCallAuditor auditor){
        return new com.jijing.fund.agent.mcp.FundMcpServer(auditor);
    }
    @Bean FundAgentSafetyPolicy fundAgentSafetyPolicy(){return new FundAgentSafetyPolicy();}
    @Bean FundAgentCitationPolicy fundAgentCitationPolicy(){return new FundAgentCitationPolicy();}
    @Bean FundProfileTool fundProfileTool(FundQueryUseCase useCase,Validator validator){return new FundProfileTool(useCase,validator);}
    @Bean FundNavTool fundNavTool(FundQueryUseCase useCase,Validator validator){return new FundNavTool(useCase,validator);}
    @Bean FundMetricsTool fundMetricsTool(FundMetricsQueryUseCase useCase,Validator validator){return new FundMetricsTool(useCase,validator);}
    @Bean FundComparisonTool fundComparisonTool(FundComparisonUseCase useCase,Validator validator){return new FundComparisonTool(useCase,validator);}
    @Bean ToolEvidenceFactory toolEvidenceFactory(){return new ToolEvidenceFactory();}
    @Bean FundRealtimeQuoteTool fundRealtimeQuoteTool(RealtimeFundQuoteUseCase useCase,ToolEvidenceFactory evidenceFactory){return new FundRealtimeQuoteTool(useCase,evidenceFactory);}
    @Bean SectorOutlookTool sectorOutlookTool(){return new SectorOutlookTool();}
    @Bean FundCatalystResearchTool fundCatalystResearchTool(){return new FundCatalystResearchTool();}
    @Bean PersonalFundTool personalFundTool(WatchlistUseCase watchlists,PortfolioUseCase portfolios){return new PersonalFundTool(watchlists,portfolios);}
    @Bean @ConditionalOnProperty(prefix="fund.knowledge",name="enabled",havingValue="true")
    FundDocumentSearchTool fundDocumentSearchTool(KnowledgeSearchUseCase useCase,Validator validator){return new FundDocumentSearchTool(useCase,validator);}
    @Bean FundToolRouter fundToolRouter(FundProfileTool profile,FundNavTool nav,FundMetricsTool metrics,FundComparisonTool comparison,ObjectProvider<FundDocumentSearchTool> documents,FundRealtimeQuoteTool realtime,SectorOutlookTool sector,FundCatalystResearchTool catalyst,PersonalFundTool personal){return new FundToolRouter(profile,nav,metrics,comparison,documents.getIfAvailable(),realtime,sector,catalyst,personal);}
    @Bean
    @ConditionalOnProperty(prefix="fund.agent",name="enabled",havingValue="true")
    ChatMemory fundAgentChatMemory(ChatMemoryRepository repository,FundAgentProperties properties){
        return new TokenBudgetChatMemory(repository,properties.maxConversationTokens(),properties.maxConversationMessages());
    }
}
