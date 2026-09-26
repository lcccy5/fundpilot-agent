package com.jijing.fund.agent.tool;

import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.application.research.RealtimeFundQuoteResult;
import com.jijing.fund.application.research.RealtimeFundQuoteStatus;
import com.jijing.fund.application.research.RealtimeFundQuoteUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 把场外基金关联的场内 ETF 行情适配成工具结果。行情的 HTTP 访问和解析不在本类。
 * 没有代理行情时返回数据未就绪；参数不被支持时返回可修正错误；数据源失败时返回固定的暂不可用说明。
 * 缺少执行轨迹时直接抛出 IllegalStateException。
 */
public final class FundRealtimeQuoteTool {
    public static final String NAME = "fund_realtime_quote";
    private static final String VERSION = "fund-realtime-v2";
    private final RealtimeFundQuoteUseCase useCase;
    private final ToolEvidenceFactory evidenceFactory;

    /**
     * 绑定实时行情用例和证据工厂。任一参数为 null 时立即抛出 NullPointerException。
     */
    public FundRealtimeQuoteTool(RealtimeFundQuoteUseCase useCase, ToolEvidenceFactory evidenceFactory) {
        this.useCase = Objects.requireNonNull(useCase);
        this.evidenceFactory = Objects.requireNonNull(evidenceFactory);
    }

    /**
     * 查询关联 ETF 的实时价格。过期行情仍返回数据和证据，但状态为 DATA_NOT_READY。
     * 行情对象为空时改走不可用分支。非法参数记录 REALTIME_QUOTE_UNSUPPORTED；其他运行时失败记录 REALTIME_QUOTE_FAILED，
     * 且不把底层异常原文回给模型。方法上的 @Valid 不会在这里执行。
     */
    @Tool(name = NAME, description = "查询ETF联接基金关联场内ETF的实时行情，返回实时价格、涨跌幅和行情时间。该结果是代理指标，不是场外基金官方净值。适用于今天、当前、实时、盘中涨跌问题。")
    public FundToolEnvelope<RealtimeQuote> quote(@Valid Input input, ToolContext context) {
        AgentExecutionTrace trace = FundToolSupport.trace(context);
        var call = trace.begin(NAME, input);
        try {
            RealtimeFundQuoteResult result = trace.call(call, () -> useCase.query(input.fundCode()));
            if (result.quote() == null) {
                trace.success(call, List.of());
                return unavailable(result);
            }
            RealtimeQuote data = map(result);
            var evidence = evidenceFactory.create(NAME, input.fundCode(), result.quote().lineage());
            trace.success(call, evidence, data);
            ToolResultStatus status = result.status() == RealtimeFundQuoteStatus.STALE
                    ? ToolResultStatus.DATA_NOT_READY : ToolResultStatus.SUCCESS;
            return new FundToolEnvelope<>(NAME, VERSION, status, data, evidence, result.limitations(), null, null);
        } catch (IllegalArgumentException error) {
            trace.failure(call, "REALTIME_QUOTE_UNSUPPORTED");
            return new FundToolEnvelope<>(NAME, VERSION, ToolResultStatus.USER_CORRECTABLE, null, List.of(), List.of(),
                    "REALTIME_QUOTE_UNSUPPORTED", error.getMessage());
        } catch (RuntimeException error) {
            trace.failure(call, "REALTIME_QUOTE_FAILED");
            return new FundToolEnvelope<>(NAME, VERSION, ToolResultStatus.DATA_NOT_READY, null, List.of(), List.of(),
                    "REALTIME_QUOTE_FAILED", "实时行情数据源暂不可用");
        }
    }

    /**
     * 把没有行情对象的结果转成未就绪信封。状态却是 AVAILABLE 或 STALE 时抛出 IllegalStateException，
     * 该异常会被 quote 的运行时分支收成 REALTIME_QUOTE_FAILED。
     */
    private FundToolEnvelope<RealtimeQuote> unavailable(RealtimeFundQuoteResult result) {
        return switch (result.status()) {
            case NO_EXCHANGE_PROXY -> new FundToolEnvelope<>(NAME, VERSION, ToolResultStatus.DATA_NOT_READY, null, List.of(),
                    result.limitations(), "NO_EXCHANGE_PROXY", "该基金没有可用的关联场内ETF实时行情");
            case DATA_NOT_READY -> new FundToolEnvelope<>(NAME, VERSION, ToolResultStatus.DATA_NOT_READY, null, List.of(),
                    result.limitations(), "REALTIME_QUOTE_UNAVAILABLE", "关联ETF实时行情暂不可用");
            case AVAILABLE, STALE -> throw new IllegalStateException("available result must include quote");
        };
    }

    /**
     * 把行情视图收成工具记录。谱系里没有提供者时数据源记为 unknown，不因此失败。
     */
    private RealtimeQuote map(RealtimeFundQuoteResult result) {
        var value = result.quote().value();
        String source = result.quote().lineage().inputs().stream().map(input -> input.providerId().value()).distinct()
                .reduce((left, right) -> left + "+" + right).orElse("unknown");
        return new RealtimeQuote(value.fundCode(), value.proxyEtfCode(), value.proxyEtfName(), value.currentPrice(),
                value.previousClose(), value.priceChange(), value.changePercent(), value.quoteTime(), source,
                value.dataKind().name(), value.disclaimer());
    }

    /**
     * 实时行情入参。基金代码须为 6 位数字；该约束依赖外部校验，构造器和 quote 都不会主动执行它。
     */
    public record Input(@Pattern(regexp = "\\d{6}") String fundCode) {}

    /**
     * 返回给模型的代理行情。它不是场外基金的官方净值，disclaimer 必须随结果保留。
     */
    public record RealtimeQuote(String fundCode, String proxyEtfCode, String proxyEtfName, BigDecimal currentPrice,
                                BigDecimal previousClose, BigDecimal priceChange, BigDecimal changePercent,
                                Instant quoteTime, String dataSource, String quoteType, String disclaimer) {}
}
