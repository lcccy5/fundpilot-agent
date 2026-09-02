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

/** Agent adapter for an ETF proxy quote. Provider HTTP and parsing are intentionally outside this class. */
public final class FundRealtimeQuoteTool {
    public static final String NAME = "fund_realtime_quote";
    private static final String VERSION = "fund-realtime-v2";
    private final RealtimeFundQuoteUseCase useCase;
    private final ToolEvidenceFactory evidenceFactory;

    
    /** 执行该 Agent 运行时组件中的 FundRealtimeQuoteTool 操作。 */
    public FundRealtimeQuoteTool(RealtimeFundQuoteUseCase useCase, ToolEvidenceFactory evidenceFactory) {
        this.useCase = Objects.requireNonNull(useCase);
        this.evidenceFactory = Objects.requireNonNull(evidenceFactory);
    }

    @Tool(name = NAME, description = "查询ETF联接基金关联场内ETF的实时行情，返回实时价格、涨跌幅和行情时间。该结果是代理指标，不是场外基金官方净值。适用于今天、当前、实时、盘中涨跌问题。")
    
    /** 执行该 Agent 运行时组件中的 quote 操作。 */
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

    
    /** 执行该 Agent 运行时组件中的 unavailable 操作。 */
    private FundToolEnvelope<RealtimeQuote> unavailable(RealtimeFundQuoteResult result) {
        return switch (result.status()) {
            case NO_EXCHANGE_PROXY -> new FundToolEnvelope<>(NAME, VERSION, ToolResultStatus.DATA_NOT_READY, null, List.of(),
                    result.limitations(), "NO_EXCHANGE_PROXY", "该基金没有可用的关联场内ETF实时行情");
            case DATA_NOT_READY -> new FundToolEnvelope<>(NAME, VERSION, ToolResultStatus.DATA_NOT_READY, null, List.of(),
                    result.limitations(), "REALTIME_QUOTE_UNAVAILABLE", "关联ETF实时行情暂不可用");
            case AVAILABLE, STALE -> throw new IllegalStateException("available result must include quote");
        };
    }

    
    /** 执行该 Agent 运行时组件中的 map 操作。 */
    private RealtimeQuote map(RealtimeFundQuoteResult result) {
        var value = result.quote().value();
        String source = result.quote().lineage().inputs().stream().map(input -> input.providerId().value()).distinct()
                .reduce((left, right) -> left + "+" + right).orElse("unknown");
        return new RealtimeQuote(value.fundCode(), value.proxyEtfCode(), value.proxyEtfName(), value.currentPrice(),
                value.previousClose(), value.priceChange(), value.changePercent(), value.quoteTime(), source,
                value.dataKind().name(), value.disclaimer());
    }

    
    /** 在 Agent 运行时边界间传递 Input 数据的不可变值对象。 */
    public record Input(@Pattern(regexp = "\\d{6}") String fundCode) {}
    
    /** 在 Agent 运行时边界间传递 RealtimeQuote 数据的不可变值对象。 */
    public record RealtimeQuote(String fundCode, String proxyEtfCode, String proxyEtfName, BigDecimal currentPrice,
                                BigDecimal previousClose, BigDecimal priceChange, BigDecimal changePercent,
                                Instant quoteTime, String dataSource, String quoteType, String disclaimer) {}
}
