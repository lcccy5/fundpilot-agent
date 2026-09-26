package com.jijing.fund.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.capability.AgentCapabilityExecutor;
import com.jijing.fund.agent.capability.CapabilityExecutorRegistry;
import com.jijing.fund.agent.capability.CatalystResearchCapabilityExecutor;
import com.jijing.fund.agent.capability.DeclineAttributionCapabilityGraphExecutor;
import com.jijing.fund.agent.capability.FundComparisonCapabilityExecutor;
import com.jijing.fund.agent.capability.FundMetricsCapabilityExecutor;
import com.jijing.fund.agent.capability.PortfolioSnapshotCapabilityExecutor;
import com.jijing.fund.agent.capability.ReportExportCapabilityExecutor;
import com.jijing.fund.agent.capability.ReportVerifyCapabilityExecutor;
import com.jijing.fund.agent.capability.ReportWriteCapabilityExecutor;
import com.jijing.fund.agent.event.DomainEventDispatcher;
import com.jijing.fund.agent.execution.AgentCoordinator;
import com.jijing.fund.agent.execution.PlanTaskWorker;
import com.jijing.fund.agent.graph.GraphCheckpointStore;
import com.jijing.fund.agent.graph.InMemoryGraphCheckpointStore;
import com.jijing.fund.agent.mcp.FundMcpServer;
import com.jijing.fund.agent.mcp.McpCallAuditor;
import com.jijing.fund.agent.notification.InAppNotificationChannel;
import com.jijing.fund.agent.notification.NotificationDispatcher;
import com.jijing.fund.agent.notification.NotificationStore;
import com.jijing.fund.agent.planning.RuleBasedPlanner;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.report.MonthlyReportLauncher;
import com.jijing.fund.agent.report.ReportJobStore;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import com.jijing.fund.agent.routing.RouteAdvisor;
import com.jijing.fund.agent.routing.SpringAiRouteAdvisor;
import com.jijing.fund.agent.tool.FundCatalystResearchTool;
import com.jijing.fund.agent.tool.FundComparisonTool;
import com.jijing.fund.agent.tool.FundDocumentSearchTool;
import com.jijing.fund.agent.tool.FundMetricsTool;
import com.jijing.fund.agent.tool.FundNavTool;
import com.jijing.fund.agent.tool.FundProfileTool;
import com.jijing.fund.agent.tool.FundRealtimeQuoteTool;
import com.jijing.fund.agent.tool.FundToolRouter;
import com.jijing.fund.agent.tool.PersonalFundTool;
import com.jijing.fund.agent.tool.SectorOutlookTool;
import com.jijing.fund.agent.tool.ToolEvidenceFactory;
import com.jijing.fund.agent.api.AgentRunUseCase;
import com.jijing.fund.application.FundComparisonUseCase;
import com.jijing.fund.application.FundMetricsQueryUseCase;
import com.jijing.fund.application.FundQueryUseCase;
import com.jijing.fund.application.portfolio.PortfolioUseCase;
import com.jijing.fund.application.research.RealtimeFundQuoteUseCase;
import com.jijing.fund.application.watchlist.WatchlistUseCase;
import com.jijing.fund.domain.identity.UserAccountRepository;
import com.jijing.fund.knowledge.api.KnowledgeSearchUseCase;
import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 装配基金 Agent 的提示词、路由、工具和有界运行组件。
 * 语义路由缺少密钥时注册空顾问，请求失败开放到确定性规则，而不是启动一个会失败的模型客户端。
 * 提示词资源缺失时应用无法启动。计划校验、审批拒绝和对等代理失败由各自运行时处理，不在装配期吞掉。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FundAgentProperties.class)
public class FundAgentConfiguration {

    /**
     * 从类路径加载当前配置指定的提示词。
     * 资源不存在时抛出 {@link IllegalStateException}，进程不能带着空提示词接收流量。
     */
    @Bean
    @ConditionalOnProperty(prefix = "fund.agent", name = "enabled", havingValue = "true")
    FundAgentPrompt fundAgentPrompt(ResourceLoader resources, FundAgentProperties properties) throws java.io.IOException {
        var resource = resources.getResource("classpath:prompts/" + properties.promptVersion() + ".txt");
        if (!resource.exists()) {
            throw new IllegalStateException("Missing agent prompt resource: " + properties.promptVersion());
        }
        String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        return new FundAgentPrompt(properties.promptVersion(), content, AgentExecutionTrace.sha256(content));
    }

    /**
     * 在未启用 AgentOps 解析时，每次请求都返回这份本地提示词。
     * 远程解析失败的回退策略因此不会生效，强制版本也无法改写本地正文。
     */
    @Bean
    @ConditionalOnMissingBean(FundAgentPromptResolver.class)
    FundAgentPromptResolver localFundAgentPromptResolver(FundAgentPrompt prompt) {
        return request -> ResolvedFundAgentPrompt.local(prompt);
    }

    /**
     * 从环境属性读取主聊天模型的供应方和模型名。
     * 属性缺失时使用占位名称，不把配置缺失伪装成一次路由失败。
     */
    @Bean
    AgentModelDescriptor agentModelDescriptor(Environment environment) {
        return new AgentModelDescriptor(environment.getProperty("spring.ai.model.chat", "unknown"),
                environment.getProperty("spring.ai.openai.chat.options.model", "configured"));
    }

    /**
     * 在语义路由开启时创建专用预检顾问。
     * 没有密钥时返回始终为空的顾问，路由器按确定性规则失败开放，不会把缺失密钥升级成计划执行。
     */
    @Bean
    @ConditionalOnProperty(prefix = "fund.agent.routing", name = "semantic-enabled", havingValue = "true")
    RouteAdvisor semanticRouteAdvisor(ObjectMapper mapper, Environment environment, ObservationRegistry observations) {
        Duration timeout = environment.getProperty("fund.agent.routing.semantic-timeout", Duration.class,
                Duration.ofSeconds(3));
        String apiKey = environment.getProperty("fund.agent.routing.api-key", "");
        if (!StringUtils.hasText(apiKey)) {
            return (message, features) -> Optional.empty();
        }
        String baseUrl = environment.getProperty("fund.agent.routing.base-url",
                "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String model = environment.getProperty("fund.agent.routing.model", "qwen3.8-flash");
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(environment.getProperty("fund.agent.routing.connect-timeout", Duration.class,
                        Duration.ofSeconds(1)))
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(environment.getProperty("fund.agent.routing.read-timeout", Duration.class, timeout));
        var api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .completionsPath("/chat/completions")
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        var options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.0)
                .maxTokens(400)
                .parallelToolCalls(false)
                .extraBody(Map.of("enable_thinking", false))
                .build();
        var routerModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .observationRegistry(observations)
                .build();
        return new SpringAiRouteAdvisor(routerModel, mapper, timeout);
    }

    /**
     * 创建执行模式路由器。
     * 容器里没有顾问时使用空建议，未知语义请求留在有界 ReAct。
     */
    @Bean
    ExecutionModeRouter executionModeRouter(ObjectProvider<RouteAdvisor> advisors, Environment environment) {
        RouteAdvisor advisor = advisors.getIfAvailable(() -> (message, features) -> Optional.empty());
        return new ExecutionModeRouter(advisor);
    }

    /**
     * 用已注册的能力执行器组成注册表。
     * 计划里出现注册表没有的任务类型时，由计划校验或执行器拒绝，而不是在这里补一个空实现。
     */
    @Bean
    CapabilityExecutorRegistry capabilityExecutorRegistry(List<AgentCapabilityExecutor> executors) {
        return new CapabilityExecutorRegistry(executors);
    }

    /**
     * 在没有 JDBC 图检查点适配器时提供内存实现。
     * 进程退出后检查点消失，恢复失败的研究任务不能从这份存储里继续。
     */
    @Bean
    @ConditionalOnMissingBean(GraphCheckpointStore.class)
    GraphCheckpointStore graphCheckpointStore() {
        return new InMemoryGraphCheckpointStore();
    }

    /**
     * 注册基金指标能力。
     * 查询失败由执行器向外抛出，计划任务不会被记成成功。
     */
    @Bean
    AgentCapabilityExecutor fundMetricsCapabilityExecutor(FundMetricsQueryUseCase useCase, ObjectMapper mapper) {
        return new FundMetricsCapabilityExecutor(useCase, mapper);
    }

    /**
     * 注册基金对比能力。
     * 对比失败时任务失败，不会退回单只基金的部分结果冒充对比。
     */
    @Bean
    AgentCapabilityExecutor fundComparisonCapabilityExecutor(FundComparisonUseCase useCase, ObjectMapper mapper) {
        return new FundComparisonCapabilityExecutor(useCase, mapper);
    }

    /**
     * 注册报告核验能力。
     * 核验不通过时任务失败，撰写不得提前消费未核验的草稿。
     */
    @Bean
    AgentCapabilityExecutor reportVerifyCapabilityExecutor(AgentDagRepository dag) {
        return new ReportVerifyCapabilityExecutor(dag);
    }

    /**
     * 注册报告撰写能力。
     * 上游核验失败时该任务不应被调度；执行期失败会留在任务状态里。
     */
    @Bean
    AgentCapabilityExecutor reportWriteCapabilityExecutor(AgentDagRepository dag) {
        return new ReportWriteCapabilityExecutor(dag);
    }

    /**
     * 注册组合快照能力。
     * 身份不匹配时由执行器拒绝，计划输入里不允许自带用户标识。
     */
    @Bean
    AgentCapabilityExecutor portfolioSnapshotCapabilityExecutor(PortfolioUseCase useCase, UserAccountRepository accounts,
            ObjectMapper mapper, Clock clock) {
        return new PortfolioSnapshotCapabilityExecutor(useCase, accounts, mapper, clock);
    }

    /**
     * 注册报告导出能力。
     * 该能力属于需审批名单；审批拒绝时调用方不得把它当成已经导出。
     */
    @Bean
    AgentCapabilityExecutor reportExportCapabilityExecutor(AgentDagRepository dag, ObjectMapper mapper) {
        return new ReportExportCapabilityExecutor(dag, mapper);
    }

    /**
     * 把 LangGraph4j 催化子图注册成服务端控制的能力。
     * 子图失败时外层计划任务失败，不会拆成未校验的额外任务。
     */
    @Bean
    AgentCapabilityExecutor catalystResearchCapabilityExecutor(FundCatalystResearchTool tool, AgentDagRepository dag,
            GraphCheckpointStore checkpoints, ObjectMapper mapper) {
        return new CatalystResearchCapabilityExecutor(tool, dag, checkpoints, mapper);
    }

    /**
     * 注册下跌归因图能力。
     * 图执行失败时归因任务失败，监督者不能追加计划外的补救任务。
     */
    @Bean
    AgentCapabilityExecutor declineAttributionCapabilityExecutor(FundCatalystResearchTool tool, AgentDagRepository dag,
            GraphCheckpointStore checkpoints, ObjectMapper mapper) {
        return new DeclineAttributionCapabilityGraphExecutor(tool, dag, checkpoints, mapper);
    }

    /**
     * 创建按能力注册表执行计划任务的工作者。
     * 未知任务类型或执行器失败时任务保持失败，不会被工作者改写成成功。
     */
    @Bean
    PlanTaskWorker planTaskWorker(AgentDagRepository dag, CapabilityExecutorRegistry executors) {
        return new PlanTaskWorker(dag, executors);
    }

    /**
     * 创建把路由结果落到持久化计划的协调器。
     * 路由拒绝权限时协调器不创建运行。规划器使用应用时钟，避免区间随测试时钟漂移。
     */
    @Bean
    AgentCoordinator agentCoordinator(ExecutionModeRouter router, AgentDagRepository dag, PlanTaskWorker worker,
            Clock clock) {
        return new AgentCoordinator(router, dag, worker, new RuleBasedPlanner(clock));
    }

    /**
     * 创建只走应用内通道的通知分发器。
     * 通道拒绝投递时通知保持未送达，不会改由多代理重试。
     */
    @Bean
    NotificationDispatcher notificationDispatcher(NotificationStore store) {
        return new NotificationDispatcher(store, new InAppNotificationChannel());
    }

    /**
     * 创建领域事件分发器。
     * 未知事件模式进入死信，不会启动多代理或改写计划。
     */
    @Bean
    DomainEventDispatcher domainEventDispatcher(NotificationDispatcher notifications) {
        return new DomainEventDispatcher(notifications);
    }

    /**
     * 创建月报启动器。
     * 多代理质量门槛未达到时月报仍可走单代理计划；审批和路由规则不被启动器放宽。
     */
    @Bean
    MonthlyReportLauncher monthlyReportLauncher(AgentRunUseCase runs, ReportJobStore jobs, Clock clock) {
        return new MonthlyReportLauncher(runs, jobs, clock);
    }

    /**
     * 创建进程内基金 MCP 服务。
     * 模式摘要变化或缺少认证时调用被拒绝，不会继续执行组合查询。
     */
    @Bean
    FundMcpServer fundMcpServer(McpCallAuditor auditor) {
        return new FundMcpServer(auditor);
    }

    /**
     * 提供输入和回答的安全闸门。
     * 命中规则时运行被拒绝，不会进入模型或计划。
     */
    @Bean
    FundAgentSafetyPolicy fundAgentSafetyPolicy() {
        return new FundAgentSafetyPolicy();
    }

    /**
     * 提供回答引用校验。
     * 证据不兼容时回答被拒绝，不会把未引用数字当成事实返回。
     */
    @Bean
    FundAgentCitationPolicy fundAgentCitationPolicy() {
        return new FundAgentCitationPolicy();
    }

    /**
     * 注册基金概况工具。
     * 参数校验失败时工具调用失败，并记入运行追踪。
     */
    @Bean
    FundProfileTool fundProfileTool(FundQueryUseCase useCase, Validator validator) {
        return new FundProfileTool(useCase, validator);
    }

    /**
     * 注册基金净值工具。
     * 查询失败时该次工具记失败，不写入事实卡。
     */
    @Bean
    FundNavTool fundNavTool(FundQueryUseCase useCase, Validator validator) {
        return new FundNavTool(useCase, validator);
    }

    /**
     * 注册基金指标工具。
     * 指标计算失败时不把空结果当成可引用证据。
     */
    @Bean
    FundMetricsTool fundMetricsTool(FundMetricsQueryUseCase useCase, Validator validator) {
        return new FundMetricsTool(useCase, validator);
    }

    /**
     * 注册基金对比工具。
     * 对比失败时本轮不能引用尚未产生的对比证据。
     */
    @Bean
    FundComparisonTool fundComparisonTool(FundComparisonUseCase useCase, Validator validator) {
        return new FundComparisonTool(useCase, validator);
    }

    /**
     * 提供工具证据的统一构造。
     * 缺少来源字段时证据仍可生成，但回答限制会标明来源未确认。
     */
    @Bean
    ToolEvidenceFactory toolEvidenceFactory() {
        return new ToolEvidenceFactory();
    }

    /**
     * 注册实时行情工具。
     * 行情失败时不写入短有效期事实卡，避免把失败观察留在记忆里。
     */
    @Bean
    FundRealtimeQuoteTool fundRealtimeQuoteTool(RealtimeFundQuoteUseCase useCase, ToolEvidenceFactory evidenceFactory) {
        return new FundRealtimeQuoteTool(useCase, evidenceFactory);
    }

    /**
     * 注册板块展望工具。
     * 工具自身失败由调用追踪记录，不升级为计划执行。
     */
    @Bean
    SectorOutlookTool sectorOutlookTool() {
        return new SectorOutlookTool();
    }

    /**
     * 注册催化研究工具，供有界运行和图能力共用。
     * 研究失败时对应任务或工具调用失败，不自动追加计划外步骤。
     */
    @Bean
    FundCatalystResearchTool fundCatalystResearchTool() {
        return new FundCatalystResearchTool();
    }

    /**
     * 注册自选和组合工具。
     * 未认证或越权访问时工具失败，不会读取其他用户的持仓。
     */
    @Bean
    PersonalFundTool personalFundTool(WatchlistUseCase watchlists, PortfolioUseCase portfolios) {
        return new PersonalFundTool(watchlists, portfolios);
    }

    /**
     * 在知识库启用时注册文档检索工具。
     * 知识库关闭时该工具不存在，文档问题不能假装已经检索过。
     */
    @Bean
    @ConditionalOnProperty(prefix = "fund.knowledge", name = "enabled", havingValue = "true")
    FundDocumentSearchTool fundDocumentSearchTool(KnowledgeSearchUseCase useCase, Validator validator) {
        return new FundDocumentSearchTool(useCase, validator);
    }

    /**
     * 按问题把可用工具交给模型。
     * 文档工具缺失时路由器不包含它；模型仍试图调用未注册工具时由安全策略或模型协议拒绝。
     */
    @Bean
    FundToolRouter fundToolRouter(FundProfileTool profile, FundNavTool nav, FundMetricsTool metrics,
            FundComparisonTool comparison, ObjectProvider<FundDocumentSearchTool> documents,
            FundRealtimeQuoteTool realtime, SectorOutlookTool sector, FundCatalystResearchTool catalyst,
            PersonalFundTool personal) {
        return new FundToolRouter(profile, nav, metrics, comparison, documents.getIfAvailable(), realtime, sector,
                catalyst, personal);
    }

    /**
     * 在 Agent 启用时提供按 token 和条数裁剪的会话记忆。
     * 预算非正时构造失败，应用不能带着会拒绝一切写入的记忆启动。
     */
    @Bean
    @ConditionalOnProperty(prefix = "fund.agent", name = "enabled", havingValue = "true")
    ChatMemory fundAgentChatMemory(ChatMemoryRepository repository, FundAgentProperties properties) {
        return new TokenBudgetChatMemory(repository, properties.maxConversationTokens(),
                properties.maxConversationMessages());
    }
}
