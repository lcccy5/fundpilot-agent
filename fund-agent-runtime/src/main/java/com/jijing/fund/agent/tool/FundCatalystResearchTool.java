package com.jijing.fund.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.exception.AgentExecutionLimitException;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 对基金或主题做持仓穿透、产业链映射、可信公告检索和暴露影响评估。
 * 优先使用 ETF 日频申赎清单和最新行情；没有可验证的日频篮子时直接拒绝，不把定期报告持仓伪装成实时持仓。
 * 参数不合法时返回可修正错误；外部数据源失败时返回数据未就绪。执行预算耗尽时原样抛出，不收成信封。
 * 缺少执行轨迹时直接抛出 IllegalStateException。方法上的校验注解不会在 research 内执行。
 */
public final class FundCatalystResearchTool {
    public static final String NAME = "research_fund_catalysts";
    static final String HOLDING_STEP = "get_fund_portfolio_exposure";
    static final String CHAIN_STEP = "map_industry_chain_exposure";
    static final String EVENT_STEP = "search_verified_market_events";
    static final String IMPACT_STEP = "assess_event_fund_impact";
    private static final String VERSION = "fund-catalyst-v1";
    private static final String UA = "Mozilla/5.0 FundPilot/1.0";
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final Pattern DISCLOSURE_DATE = Pattern.compile("截止至：<font[^>]*>(\\d{4}-\\d{2}-\\d{2})</font>");
    private static final Pattern FUND_NAME = Pattern.compile("<a title='([^']+)' href='http://fund\\.eastmoney\\.com/\\d{6}\\.html'>");
    private static final Pattern HOLDING_ROW = Pattern.compile("<tr><td>\\d+</td><td><a[^>]*>(\\d{6})</a></td><td class='tol'><a[^>]*>([^<]+)</a></td>.*?<td class='xglj'>.*?</td><td class='tor'>([\\d.]+)%</td>", Pattern.DOTALL);
    private static final Pattern PCF_NAME = Pattern.compile("\\s{2,}(.+?)\\u7533\\u8d2d\\u8d4e\\u56de\\u6e05\\u5355");
    private static final Pattern PCF_ROW = Pattern.compile("(?m)^\\s*(\\d{6})\\s+(.+?)\\s{2,}([\\d,]+)\\s+");
    private static final Pattern TENCENT_QUOTE = Pattern.compile("v_(?:sh|sz)(\\d{6})=\\\"([^\\\"]*)\\\"");
    private static final List<String> POSITIVE = List.of("预增", "扭亏", "增长", "增持", "回购", "中标", "签订", "获批", "分红", "战略合作", "发行结果");
    private static final List<String> NEGATIVE = List.of("预亏", "首亏", "续亏", "预减", "减持", "立案", "处罚", "诉讼", "终止", "退市", "风险提示", "下调", "亏损");

    private final ObjectMapper mapper;
    private final Clock clock;
    private final RestClient fundSearch;
    private final RestClient holdings;
    private final RestClient companyProfile;
    private final RestClient announcements;
    private final RestClient fundPosition;
    private final RestClient szsePcf;
    private final RestClient ssePcf;
    private final RestClient quotes;

    
    /**
     * 使用系统时钟和默认的公开数据客户端。某个客户端连不上时，研究步骤会按数据未就绪或不受支持返回，而不是在构造时失败。
     */
    public FundCatalystResearchTool() {
        this(new ObjectMapper(), Clock.system(CHINA), client("https://fundsuggest.eastmoney.com"),
                client("https://fundf10.eastmoney.com"), client("https://emweb.securities.eastmoney.com"),
                client("https://np-anotice-stock.eastmoney.com"), client("https://fundmobapi.eastmoney.com"),
                client("https://reportdocs.static.szse.cn"), client("https://query.sse.com.cn"), client("https://qt.gtimg.cn"));
    }

    /**
     * 绑定可替换的时钟和数据客户端，供同包测试注入失败。任一客户端为 null 时，首次访问该数据源会抛出 NullPointerException。
     */
    FundCatalystResearchTool(ObjectMapper mapper, Clock clock, RestClient fundSearch, RestClient holdings,
            RestClient companyProfile, RestClient announcements, RestClient fundPosition, RestClient szsePcf,
            RestClient ssePcf, RestClient quotes) {
        this.mapper = mapper;
        this.clock = clock;
        this.fundSearch = fundSearch;
        this.holdings = holdings;
        this.companyProfile = companyProfile;
        this.announcements = announcements;
        this.fundPosition = fundPosition;
        this.szsePcf = szsePcf;
        this.ssePcf = ssePcf;
        this.quotes = quotes;
    }

    
    /**
     * 创建带 3 秒连接和 6 秒读取超时的客户端。地址无效时要到第一次请求才失败。
     */
    private static RestClient client(String baseUrl) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(6));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, UA).build();
    }

    @Tool(name = NAME, description = "研究一只中国公募基金、ETF或行业主题最近可能存在的真实利好和利空。工具会优先解析 ETF 或 ETF 联接基金的目标 ETF，读取当日/T-1 申购赎回篮子并结合最新行情估算成分权重；无法获取时才使用最近公开披露持仓。随后映射重仓股行业与主营业务，查询可核验的上市公司公告，并按权重评估影响。日频篮子不等于基金公司完整实时会计持仓；未核实传言不会作为事件。theme与fundCode至少提供一个。")
    
    /**
     * 依次执行持仓、产业链、公告和影响评估。任一步失败都会先记下该步的失败码再抛出。
     * 执行次数或重复调用超限时原样抛出 AgentExecutionLimitException。
     * 基金或主题无法解析、没有日频篮子时返回 CATALYST_RESEARCH_UNSUPPORTED。
     * 其他运行时失败返回 CATALYST_DATA_UNAVAILABLE，且不把底层异常原文回给模型。
     */
    public FundToolEnvelope<CatalystResearch> research(@Valid Input input, ToolContext context) {
        AgentExecutionTrace trace = FundToolSupport.trace(context);
        try {
            var holdingCall = trace.begin(HOLDING_STEP, input);
            ResolvedFund resolved;
            Portfolio portfolio;
            try {
                resolved = resolve(input);
                portfolio = fetchPortfolio(resolved, input.topN());
                trace.success(holdingCall, holdingEvidence(portfolio),portfolio);
            } catch (RuntimeException ex) {
                trace.failure(holdingCall, "FUND_HOLDING_UNAVAILABLE");
                throw ex;
            }

            var chainCall = trace.begin(CHAIN_STEP, Map.of("stockCodes", portfolio.holdings().stream().map(Holding::stockCode).toList()));
            List<IndustryExposure> industries;
            try {
                industries = mapIndustries(portfolio.holdings(), resolved.theme());
                trace.success(chainCall, industryEvidence(portfolio, industries),industries);
            } catch (RuntimeException ex) {
                trace.failure(chainCall, "INDUSTRY_MAPPING_UNAVAILABLE");
                throw ex;
            }

            int days = input.lookbackDays() == null ? 45 : input.lookbackDays();
            var eventCall = trace.begin(EVENT_STEP, Map.of("stockCodes", portfolio.holdings().stream().map(Holding::stockCode).toList(), "lookbackDays", days));
            List<VerifiedEvent> events;
            List<EvidenceReference> eventEvidence;
            try {
                events = fetchEvents(portfolio.holdings(), days);
                eventEvidence = eventEvidence(portfolio, events, days);
                trace.success(eventCall, eventEvidence,events);
            } catch (RuntimeException ex) {
                trace.failure(eventCall, "VERIFIED_EVENT_SOURCE_UNAVAILABLE");
                throw ex;
            }

            boolean dailyPcf = portfolio.holdingsDataMode().startsWith("DAILY_PCF");
            var impactCall = trace.begin(IMPACT_STEP, Map.of("fundCode", portfolio.fundCode(), "eventCount", events.size()));
            ImpactAssessment impact;
            EvidenceReference finalEvidence;
            try {
                impact = assess(portfolio.holdings(), events);
                finalEvidence = new EvidenceReference("ev-catalyst-" + UUID.randomUUID(),
                        "FUND_CATALYST_RESEARCH", portfolio.fundCode(), portfolio.disclosureDate(), LocalDate.now(clock),
                        dailyPcf ? "DAILY_PCF_AND_LATEST_QUOTES_WITH_VERIFIED_EVENTS" : "DISCLOSED_HOLDINGS_AND_VERIFIED_EVENTS",
                        dailyPcf ? "szse-daily-pcf+tencent-qt" : "eastmoney-public-data", Instant.now(clock).toString(),
                        VERSION, Instant.now(clock));
                trace.success(impactCall, List.of(impactEvidence(portfolio, impact, days), finalEvidence),impact);
            } catch (RuntimeException ex) {
                trace.failure(impactCall, "EVENT_IMPACT_CALCULATION_FAILED");
                throw ex;
            }

            var limitations = List.of(
                    dailyPcf ? "持仓为当日/T-1 ETF申购赎回篮子结合最新股票行情的估算，不是基金公司完整会计持仓" : "持仓来自最近一期公开披露，不代表盘中实时持仓",
                    "公司事件仅纳入可定位到上市公司公告的数据，未核实传言已排除",
                    "行业与产业链位置来自公开行业标签和主营业务摘要，复杂业务可能跨多个环节",
                    "事件方向是规则化研究分类，不等于股价必然上涨或下跌");
            CatalystResearch result = new CatalystResearch(portfolio.fundName(), portfolio.fundCode(), resolved.theme(),
                    portfolio.disclosureDate(), portfolio.disclosureType(), portfolio.coveragePercent(),
                    dailyPcf ? 0 : ChronoUnit.DAYS.between(portfolio.disclosureDate(), LocalDate.now(clock)), portfolio.holdings(),
                    industries, events, impact, Instant.now(clock),
                    portfolio.holdingsDataMode(), portfolio.underlyingEtfCode(), portfolio.underlyingEtfName(),
                    dailyPcf ? List.of("szse-daily-pcf", "tencent-qt", "eastmoney-company-profile", "eastmoney-exchange-announcement-aggregation")
                            : List.of("eastmoney-fund-holdings", "eastmoney-company-profile", "eastmoney-exchange-announcement-aggregation"),
                    limitations);
            return FundToolEnvelope.success(NAME, result, finalEvidence, limitations);
        } catch (AgentExecutionLimitException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            return new FundToolEnvelope<>(NAME, VERSION, ToolResultStatus.USER_CORRECTABLE, null, List.of(),
                    List.of(), "CATALYST_RESEARCH_UNSUPPORTED", ex.getMessage());
        } catch (RuntimeException ex) {
            return new FundToolEnvelope<>(NAME, VERSION, ToolResultStatus.DATA_NOT_READY, null, List.of(),
                    List.of(), "CATALYST_DATA_UNAVAILABLE", "持仓或公告数据源暂不可用，请稍后重试");
        }
    }

    
    /**
     * 把基金代码或主题解析成一只有公开持仓的场内基金。代码不是 6 位数字，或代码和主题都为空时抛出 IllegalArgumentException。
     * 只给主题时最多搜索 8 次；候选持仓都失败或搜索异常被吞掉后，抛出未找到对应场内基金。
     */
    private ResolvedFund resolve(Input input) {
        String code = input.fundCode() == null ? "" : input.fundCode().trim();
        String theme = normalizeTheme(input.theme());
        if (!code.isBlank()) {
            if (!code.matches("\\d{6}")) throw new IllegalArgumentException("基金代码必须是6位数字");
            return new ResolvedFund(code, theme.isBlank() ? null : theme, resolveFundName(code));
        }
        if (theme.isBlank()) throw new IllegalArgumentException("请提供基金代码或具体行业主题");
        ArrayDeque<String> queries = new ArrayDeque<>(List.of(theme + "ETF", theme));
        Set<String> seen = new HashSet<>();
        for (int searched = 0; !queries.isEmpty() && searched < 8; searched++) {
            String query = queries.removeFirst();
            if (!seen.add(query)) continue;
            try {
                String body = fundSearch.get().uri(uri -> uri.path("/FundSearch/api/FundSearchAPI.ashx")
                        .queryParam("m", 1).queryParam("key", query).build())
                        .header(HttpHeaders.REFERER, "https://fund.eastmoney.com/").retrieve().body(String.class);
                JsonNode rows = mapper.readTree(body).path("Datas");
                List<FundCandidate> candidates = new ArrayList<>();
                for (JsonNode row : rows) {
                    for (JsonNode tag : row.path("ZTJJInfo")) {
                        String category = tag.path("TTYPENAME").asText();
                        if (!category.isBlank() && !category.equals(theme)) queries.addLast(category + "ETF");
                    }
                    String candidateCode = row.path("CODE").asText();
                    String name = row.path("NAME").asText();
                    if (!candidateCode.matches("[15]\\d{5}")) continue;
                    int score = (name.contains(theme) ? 100 : 0) + (name.toUpperCase(Locale.ROOT).contains("ETF") ? 50 : 0);
                    candidates.add(new FundCandidate(candidateCode, name, score));
                }
                candidates.sort(Comparator.comparingInt(FundCandidate::score).reversed());
                for (FundCandidate candidate : candidates.stream().limit(5).toList()) {
                    try {
                        Portfolio portfolio = fetchPortfolio(new ResolvedFund(candidate.code(), theme, candidate.name()), 10);
                        if (!portfolio.holdings().isEmpty()) return new ResolvedFund(candidate.code(), theme, candidate.name());
                    } catch (RuntimeException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        throw new IllegalArgumentException("没有找到“" + theme + "”对应且具有公开持仓的场内基金");
    }

    
    /**
     * 去掉主题里的口头语和空白。传入 null 时返回空字符串，由 resolve 决定是否还缺少研究对象。
     */
    private String normalizeTheme(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)(板块|行业|主题|未来|后市|走势|一个月|几天|利好|利空|消息|新闻|分析|可能|有哪些|有什么|怎么看|\\s)", "").trim();
    }

    
    /**
     * 查询基金名称。搜索失败或没有匹配名称时返回 null，不阻断后续日频持仓链路。
     */
    private String resolveFundName(String fundCode) {
        try {
            String body = fundSearch.get().uri(uri -> uri.path("/FundSearch/api/FundSearchAPI.ashx")
                            .queryParam("m", 1).queryParam("key", fundCode).build())
                    .header(HttpHeaders.REFERER, "https://fund.eastmoney.com/").retrieve().body(String.class);
            for (JsonNode row : mapper.readTree(body).path("Datas")) {
                if (fundCode.equals(row.path("CODE").asText())) {
                    String name = row.path("NAME").asText();
                    return name.isBlank() ? null : name;
                }
            }
        } catch (Exception ignored) {
            // 名称解析失败不能阻断日频持仓链路。
        }
        return null;
    }

    
    /**
     * 解析关联 ETF 并读取日频申赎篮子。没有可验证篮子时抛出 IllegalArgumentException，不回退到定期报告持仓。
     */
    private Portfolio fetchPortfolio(ResolvedFund resolved, int requestedTopN) {
        String underlyingEtfCode = resolveUnderlyingEtf(resolved.code());
        if (underlyingEtfCode != null) {
            return fetchDailyPcfPortfolio(resolved, underlyingEtfCode, requestedTopN);
        }
        throw new IllegalArgumentException("该基金没有可验证的日频 ETF 申赎篮子；公开渠道无法提供实时完整持仓");
    }

    
    /**
     * 场内 ETF 代码直接返回自身。联接基金查询目标 ETF；响应失败或代码形态不符时返回 null，由持仓步骤拒绝继续。
     */
    private String resolveUnderlyingEtf(String fundCode) {
        if (fundCode.matches("(?:159\\d{3}|5\\d{5})")) return fundCode;
        try {
            String body = fundPosition.get().uri(uri -> uri.path("/FundMNewApi/FundMNInverstPosition")
                            .queryParam("FCODE", fundCode).queryParam("deviceid", "Wap")
                            .queryParam("plat", "Wap").queryParam("product", "EFund").queryParam("version", "2.0.0").build())
                    .header(HttpHeaders.REFERER, "https://fund.eastmoney.com/").retrieve().body(String.class);
            String code = mapper.readTree(body).path("Datas").path("ETFCODE").asText();
            return code.matches("(?:159\\d{3}|5\\d{5})") ? code : null;
        } catch (Exception ex) {
            return null;
        }
    }

    
    /**
     * 读取深交所日频篮子；上交所代码改走上交所接口。最近 8 个自然日都失败时抛出最后一次运行时异常，
     * 没有异常记录时抛出篮子不可用。行情覆盖不足或市值无效时抛出 IllegalArgumentException。
     */
    private Portfolio fetchDailyPcfPortfolio(ResolvedFund resolved, String etfCode, int requestedTopN) {
        if (etfCode.startsWith("5")) {
            return fetchSsePcfPortfolio(resolved, etfCode, requestedTopN);
        }
        int topN = Math.max(1, Math.min(requestedTopN, 20));
        RuntimeException last = null;
        for (int daysBack = 0; daysBack <= 7; daysBack++) {
            LocalDate date = LocalDate.now(clock).minusDays(daysBack);
            try {
                String stamp = date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                String body = szsePcf.get().uri(uri -> uri.path("/files/text/etf/ETF" + etfCode + stamp + ".txt").build())
                        .header(HttpHeaders.REFERER, "https://www.szse.cn/disclosure/fund/currency/index.html")
                        .retrieve().body(String.class);
                List<PcfComponent> components = parsePcf(body);
                if (components.isEmpty()) continue;
                Map<String, BigDecimal> prices = latestPrices(components);
                List<ValuedComponent> valued = components.stream().filter(c -> prices.containsKey(c.stockCode()))
                        .map(c -> new ValuedComponent(c, prices.get(c.stockCode()).multiply(BigDecimal.valueOf(c.quantity())))).toList();
                if (valued.size() < Math.max(8, components.size() * 7 / 10)) throw new IllegalArgumentException("PCF 行情覆盖不足");
                BigDecimal total = valued.stream().map(ValuedComponent::marketValue).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (total.signum() <= 0) throw new IllegalArgumentException("PCF 市值无效");
                List<Holding> rows = valued.stream().sorted(Comparator.comparing(ValuedComponent::marketValue).reversed()).limit(topN)
                        .map(v -> new Holding(v.component().stockCode(), v.component().stockName(),
                                v.marketValue().multiply(BigDecimal.valueOf(100)).divide(total, 4, RoundingMode.HALF_UP))).toList();
                String etfName = pcfName(body, etfCode);
                String fundName = resolved.code().equals(etfCode) ? etfName : Optional.ofNullable(resolved.name()).orElse(resolved.code());
                BigDecimal coverage = rows.stream().map(Holding::weightPercent).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
                String mode = resolved.code().equals(etfCode) ? "DAILY_PCF_ETF_BASKET" : "DAILY_PCF_UNDERLYING_ETF_PROXY";
                return new Portfolio(fundName, resolved.code(), date, mode, coverage, rows, etfCode, etfName, mode);
            } catch (RuntimeException ex) {
                last = ex;
            }
        }
        throw last == null ? new IllegalArgumentException("ETF 日频 PCF 不可用") : last;
    }

    /**
     * 读取上交所当日申赎清单，并用最新行情估算权重。这不是基金会计账簿持仓。
     * 清单为空、行情覆盖不足、市值无效或响应不是 JSON 时抛出 IllegalArgumentException。
     */
    private Portfolio fetchSsePcfPortfolio(ResolvedFund resolved, String etfCode, int requestedTopN) {
        String body = ssePcf.get().uri(uri -> uri.path("/commonQuery.do")
                        .queryParam("isPagination", false).queryParam("FUNDID2", etfCode)
                        .queryParam("sqlId", "COMMON_SSE_CP_JJLB_ETFJJGK_GGSGSHQD_COMPONENT_C").build())
                .header(HttpHeaders.REFERER, "https://www.sse.com.cn/disclosure/fund/etflist/detail.shtml?fundid=" + etfCode)
                .retrieve().body(String.class);
        try {
            JsonNode rows = mapper.readTree(body).path("result");
            List<PcfComponent> components = new ArrayList<>();
            for (JsonNode row : rows) {
                // 上交所该接口使用 INSTRUMENT_ID / INSTRUMENT_NAME；SECURITY_CODE 是深交所接口字段。
                String code = row.path("INSTRUMENT_ID").asText();
                long quantity = row.path("QUANTITY").asLong();
                if (code.matches("\\d{6}") && quantity > 0) {
                    components.add(new PcfComponent(code, row.path("INSTRUMENT_NAME").asText(code), quantity));
                }
            }
            if (components.isEmpty()) throw new IllegalArgumentException("上交所当日 PCF 不含可估值证券");
            Map<String, BigDecimal> prices = latestPrices(components);
            List<ValuedComponent> valued = components.stream().filter(c -> prices.containsKey(c.stockCode()))
                    .map(c -> new ValuedComponent(c, prices.get(c.stockCode()).multiply(BigDecimal.valueOf(c.quantity())))).toList();
            if (valued.size() < Math.max(8, components.size() * 7 / 10)) throw new IllegalArgumentException("上交所 PCF 行情覆盖不足");
            BigDecimal total = valued.stream().map(ValuedComponent::marketValue).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.signum() <= 0) throw new IllegalArgumentException("上交所 PCF 市值无效");
            int topN = Math.max(1, Math.min(requestedTopN, 20));
            List<Holding> holdings = valued.stream().sorted(Comparator.comparing(ValuedComponent::marketValue).reversed()).limit(topN)
                    .map(v -> new Holding(v.component().stockCode(), v.component().stockName(),
                            v.marketValue().multiply(BigDecimal.valueOf(100)).divide(total, 4, RoundingMode.HALF_UP))).toList();
            BigDecimal coverage = holdings.stream().map(Holding::weightPercent).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            return new Portfolio(Optional.ofNullable(resolved.name()).orElse(etfCode), resolved.code(), LocalDate.now(clock),
                    "DAILY_PCF_ETF_BASKET", coverage, holdings, etfCode, resolved.name(), "DAILY_PCF_ETF_BASKET");
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("上交所日频 PCF 响应无法解析", ex);
        }
    }

    /**
     * 解析深交所篮子文本。正文为空或没有组合信息段时返回空列表，不抛出异常。
     * 数量不是整数时抛出 NumberFormatException。现金替代代码 159900 会被跳过。
     */
    static List<PcfComponent> parsePcf(String body) {
        if (body == null || !body.contains("组合信息内容")) {
            return List.of();
        }
        Matcher matcher = PCF_ROW.matcher(body.substring(body.indexOf("组合信息内容")));
        List<PcfComponent> result = new ArrayList<>();
        while (matcher.find()) {
            String code = matcher.group(1);
            long quantity = Long.parseLong(matcher.group(3).replace(",", ""));
            if (quantity > 0 && !"159900".equals(code)) {
                result.add(new PcfComponent(code, matcher.group(2).trim(), quantity));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 查询成分股最新价。行情响应为空时返回空映射；单个价格不是正数或不是数字时跳过该代码，不使整批失败。
     * 行情请求本身抛出的运行时异常会传给调用方。
     */
    private Map<String, BigDecimal> latestPrices(List<PcfComponent> components) {
        String symbols = components.stream().map(c -> (c.stockCode().startsWith("6") ? "sh" : "sz") + c.stockCode())
                .collect(java.util.stream.Collectors.joining(","));
        String body = quotes.get().uri(uri -> uri.queryParam("q", symbols).build()).header(HttpHeaders.REFERER, "https://gu.qq.com/")
                .retrieve().body(String.class);
        Matcher matcher = TENCENT_QUOTE.matcher(body == null ? "" : body);
        Map<String, BigDecimal> result = new HashMap<>();
        while (matcher.find()) {
            String[] fields = matcher.group(2).split("~", -1);
            if (fields.length > 3) {
                try {
                    BigDecimal price = new BigDecimal(fields[3]);
                    if (price.signum() > 0) {
                        result.put(matcher.group(1), price);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Map.copyOf(result);
    }

    /**
     * 从篮子抬头提取基金名称。正文为空或匹配不到时返回调用方给出的兜底名称，不抛出异常。
     */
    private String pcfName(String body, String fallback) {
        Matcher matcher = PCF_NAME.matcher(body == null ? "" : body);
        return matcher.find() ? matcher.group(1).trim() : fallback;
    }

    /**
     * 读取最近一期公开披露持仓。当前持仓入口不会调用它。正文为空、缺少披露日或没有股票行时抛出 IllegalArgumentException。
     */
    private Portfolio fetchDisclosedPortfolio(ResolvedFund resolved, int requestedTopN) {
        int topN = Math.max(1, Math.min(requestedTopN, 20));
        String body = holdings.get().uri(uri -> uri.path("/FundArchivesDatas.aspx")
                        .queryParam("type", "jjcc").queryParam("code", resolved.code()).queryParam("topline", topN)
                        .queryParam("year", "").queryParam("month", "").queryParam("rt", "0.1").build())
                .header(HttpHeaders.REFERER, "https://fundf10.eastmoney.com/").retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("未取得基金公开持仓");
        }
        Matcher dateMatcher = DISCLOSURE_DATE.matcher(body);
        if (!dateMatcher.find()) {
            throw new IllegalArgumentException("公开持仓缺少披露日期");
        }
        LocalDate disclosureDate = LocalDate.parse(dateMatcher.group(1));
        Matcher nameMatcher = FUND_NAME.matcher(body);
        String fundName = nameMatcher.find() ? html(nameMatcher.group(1)) : Optional.ofNullable(resolved.name()).orElse(resolved.code());
        List<Holding> result = new ArrayList<>();
        Matcher row = HOLDING_ROW.matcher(body);
        while (row.find() && result.size() < topN) {
            result.add(new Holding(row.group(1), html(row.group(2)), decimal(row.group(3))));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("该基金最近一期未披露股票持仓");
        }
        BigDecimal coverage = result.stream().map(Holding::weightPercent).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        String type = body.contains("季度股票投资明细") ? "QUARTERLY_REPORT_TOP_HOLDINGS"
                : body.contains("半年度") ? "SEMI_ANNUAL_REPORT" : body.contains("年度") ? "ANNUAL_REPORT" : "LATEST_DISCLOSURE";
        return new Portfolio(fundName, resolved.code(), disclosureDate, type, coverage, List.copyOf(result), null, null, "DISCLOSED_REPORT_HOLDINGS");
    }

    
    /**
     * 为前 8 只持仓映射行业标签和产业链位置。单只股票的资料请求失败时写入“公开主营业务信息暂不可用”，不使整步失败。
     */
    private List<IndustryExposure> mapIndustries(List<Holding> holdings, String theme) {
        List<IndustryExposure> result = new ArrayList<>();
        for (Holding holding : holdings.stream().limit(8).toList()) {
            try {
                String marketCode = holding.stockCode().startsWith("6") ? "SH" : "SZ";
                String body = companyProfile.get().uri(uri -> uri.path("/PC_HSF10/CoreConception/PageAjax")
                                .queryParam("code", marketCode + holding.stockCode()).build())
                        .header(HttpHeaders.REFERER, "https://emweb.securities.eastmoney.com/").retrieve().body(String.class);
                JsonNode root = mapper.readTree(body);
                LinkedHashSet<String> tags = new LinkedHashSet<>();
                for (JsonNode tag : root.path("ssbk")) {
                    int rank = tag.path("BOARD_RANK").asInt(99);
                    String name = tag.path("BOARD_NAME").asText();
                    if (rank <= 3 && !name.isBlank()) tags.add(name);
                }
                String business = "";
                for (JsonNode point : root.path("hxtc")) {
                    if ("主营业务".equals(point.path("KEY_CLASSIF").asText())) {
                        business = point.path("MAINPOINT_CONTENT").asText();
                        break;
                    }
                }
                if (business.length() > 180) {
                    business = business.substring(0, 180) + "…";
                }
                String role = chainRole(theme, String.join(" ", tags) + " " + business);
                result.add(new IndustryExposure(holding.stockCode(), holding.stockName(), holding.weightPercent(),
                        role, List.copyOf(tags), business));
            } catch (Exception ex) {
                result.add(new IndustryExposure(holding.stockCode(), holding.stockName(), holding.weightPercent(),
                        "相关产业环节", List.of(), "公开主营业务信息暂不可用"));
            }
        }
        return List.copyOf(result);
    }

    
    /**
     * 用主题和公开标签粗分上、中、下游。没有命中关键词时返回“相关产业环节”，不抛出异常。
     */
    private String chainRole(String theme, String text) {
        String value = (Objects.toString(theme, "") + " " + text).toLowerCase(Locale.ROOT);
        if (containsAny(value, "减速器", "伺服", "传感器", "控制器", "电机", "丝杠", "轴承", "原材料", "化肥", "农药", "饲料", "种业")) return "上游核心材料/零部件";
        if (containsAny(value, "机器人本体", "工业机器人", "系统集成", "自动化设备", "养殖", "种植", "制造")) return "中游生产/制造";
        if (containsAny(value, "汽车", "3c", "仓储", "物流", "医疗", "屠宰", "食品加工", "应用", "服务")) return "下游应用/消费";
        return "相关产业环节";
    }

    
    /**
     * 查询前 8 只持仓的公司公告，并丢掉回看窗口之外的记录。单只股票请求失败时跳过该股票，不使整步失败。
     * 没有任何公告时返回空列表，由影响评估写成未发现可核验事件。
     */
    private List<VerifiedEvent> fetchEvents(List<Holding> holdings, int days) {
        LocalDate cutoff = LocalDate.now(clock).minusDays(days);
        Map<String, BigDecimal> weights = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        holdings.forEach(h -> {
            weights.put(h.stockCode(), h.weightPercent());
            names.put(h.stockCode(), h.stockName());
        });
        Map<String, VerifiedEvent> dedup = new LinkedHashMap<>();
        for (Holding holding : holdings.stream().limit(8).toList()) {
            try {
                String body = announcements.get().uri(uri -> uri.path("/api/security/ann")
                                .queryParam("sr", -1).queryParam("page_size", 15).queryParam("page_index", 1)
                                .queryParam("ann_type", "A").queryParam("client_source", "web")
                                .queryParam("stock_list", holding.stockCode()).build())
                        .header(HttpHeaders.REFERER, "https://data.eastmoney.com/").retrieve().body(String.class);
                for (JsonNode row : mapper.readTree(body).path("data").path("list")) {
                    LocalDate date = LocalDate.parse(row.path("notice_date").asText().substring(0, 10));
                    if (date.isBefore(cutoff)) continue;
                    String artCode = row.path("art_code").asText();
                    String title = row.path("title").asText();
                    String category = row.path("columns").isArray() && !row.path("columns").isEmpty()
                            ? row.path("columns").get(0).path("column_name").asText("公司公告") : "公司公告";
                    EventDirection direction = direction(title);
                    String url = "https://data.eastmoney.com/notices/detail/" + holding.stockCode() + "/" + artCode + ".html";
                    dedup.putIfAbsent(artCode, new VerifiedEvent(artCode, holding.stockCode(),
                            names.getOrDefault(holding.stockCode(), holding.stockName()), weights.get(holding.stockCode()),
                            title, category, direction, date, "A_AGGREGATED_OFFICIAL_DISCLOSURE",
                            "交易所公告聚合", url));
                }
            } catch (Exception ignored) {
            }
        }
        return dedup.values().stream().sorted(Comparator.comparing(VerifiedEvent::publishedDate).reversed()
                .thenComparing(e -> e.holdingWeight().negate())).limit(20).toList();
    }

    /**
     * 按标题关键词把公告分成负面、正面或中性。同时命中负面词时优先记为负面。空标题返回中性，不抛出异常。
     */
    static EventDirection direction(String title) {
        if (containsAny(title, NEGATIVE.toArray(String[]::new))) return EventDirection.NEGATIVE;
        if (containsAny(title, POSITIVE.toArray(String[]::new))) return EventDirection.POSITIVE;
        return EventDirection.NEUTRAL;
    }

    
    /**
     * 按出现对应方向公告的持仓权重评估暴露。没有事件时结论为未发现近期可核验公司事件，不把空结果当成利好或利空。
     */
    private ImpactAssessment assess(List<Holding> holdings, List<VerifiedEvent> events) {
        Set<String> positiveStocks = new HashSet<>(), negativeStocks = new HashSet<>();
        for (VerifiedEvent event : events) {
            if (event.direction() == EventDirection.POSITIVE) positiveStocks.add(event.stockCode());
            if (event.direction() == EventDirection.NEGATIVE) negativeStocks.add(event.stockCode());
        }
        BigDecimal positiveWeight = affectedWeight(holdings, positiveStocks);
        BigDecimal negativeWeight = affectedWeight(holdings, negativeStocks);
        long positiveCount = events.stream().filter(e -> e.direction() == EventDirection.POSITIVE).count();
        long negativeCount = events.stream().filter(e -> e.direction() == EventDirection.NEGATIVE).count();
        long neutralCount = events.size() - positiveCount - negativeCount;
        String bias = negativeWeight.subtract(positiveWeight).compareTo(BigDecimal.valueOf(10)) > 0 ? "负面事件暴露较高"
                : positiveWeight.subtract(negativeWeight).compareTo(BigDecimal.valueOf(10)) > 0 ? "正面事件暴露较高"
                : events.isEmpty() ? "未发现近期可核验公司事件" : "正负事件并存或影响有限";
        return new ImpactAssessment(positiveWeight, negativeWeight, positiveCount, negativeCount, neutralCount, bias,
                "受影响权重按存在对应方向公告的重仓股权重去重求和；事件标题仅做规则化分类，不代表价格必然反应");
    }

    
    /**
     * 对命中代码的持仓权重去重求和。没有命中时返回 0.00，不抛出异常。
     */
    private BigDecimal affectedWeight(List<Holding> holdings, Set<String> codes) {
        return holdings.stream().filter(h -> codes.contains(h.stockCode())).map(Holding::weightPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    
    /**
     * 为持仓快照生成证据。编号含随机部分，同一次输入重复调用不会得到相同编号。
     */
    private EvidenceReference holdingEvidence(Portfolio portfolio) {
        return new EvidenceReference("ev-holding-" + UUID.randomUUID(), "FUND_HOLDING", portfolio.fundCode(),
                portfolio.disclosureDate(), portfolio.disclosureDate(), portfolio.disclosureType(),
                portfolio.holdingsDataMode().startsWith("DAILY_PCF") ? "exchange-daily-pcf+tencent-qt" : "eastmoney-fund-holdings",
                portfolio.disclosureDate().toString(), VERSION, Instant.now(clock));
    }

    
    /**
     * 为行业映射生成证据。industries 目前不写入证据正文；映射结果为空时仍生成这条检索范围证据。
     */
    private EvidenceReference industryEvidence(Portfolio portfolio, List<IndustryExposure> industries) {
        return new EvidenceReference("ev-chain-" + UUID.randomUUID(), "INDUSTRY_CHAIN", portfolio.fundCode(),
                portfolio.disclosureDate(), LocalDate.now(clock), "PUBLIC_INDUSTRY_TAGS_AND_MAIN_BUSINESS",
                "eastmoney-company-profile", Instant.now(clock).toString(), VERSION, Instant.now(clock));
    }

    
    /**
     * 为公告检索和每条公告生成证据。没有公告时只保留检索范围证据，不伪造公司事件引用。
     */
    private List<EvidenceReference> eventEvidence(Portfolio portfolio, List<VerifiedEvent> events, int days) {
        List<EvidenceReference> result = new ArrayList<>();
        result.add(new EvidenceReference("ev-event-search-" + UUID.randomUUID(), "MARKET_EVENT_SEARCH",
                portfolio.fundCode(), LocalDate.now(clock).minusDays(days), LocalDate.now(clock),
                "VERIFIED_ANNOUNCEMENT_SEARCH", "eastmoney-exchange-announcement-aggregation",
                Instant.now(clock).toString(), VERSION, Instant.now(clock)));
        for (VerifiedEvent event : events) {
            result.add(new EvidenceReference("ev-event-" + UUID.randomUUID(), "COMPANY_ANNOUNCEMENT",
                    portfolio.fundCode(), null, null, null, "exchange-announcement-aggregation", event.publishedDate().toString(),
                    VERSION, Instant.now(clock), event.eventId(), event.eventId(), event.stockCode(), event.title(),
                    event.publishedDate(), null, null, event.category(), event.title(), event.sourceUrl()));
        }
        return List.copyOf(result);
    }

    
    /**
     * 为权重化影响评估生成证据。impact 的数值不写入证据字段，只标明评估方法和时间窗。
     */
    private EvidenceReference impactEvidence(Portfolio portfolio, ImpactAssessment impact, int days) {
        return new EvidenceReference("ev-impact-" + UUID.randomUUID(), "EVENT_IMPACT", portfolio.fundCode(),
                LocalDate.now(clock).minusDays(days), LocalDate.now(clock), "HOLDING_WEIGHTED_EVENT_EXPOSURE",
                "fund-holding+verified-announcement", Instant.now(clock).toString(), VERSION, Instant.now(clock));
    }

    
    /**
     * 判断文本是否包含任一关键词。text 为 null 时抛出 NullPointerException。
     */
    private static boolean containsAny(String text, String... values) {
        return java.util.Arrays.stream(values).anyMatch(text::contains);
    }

    
    /**
     * 把持仓百分比收成两位小数。value 不是数字时抛出 NumberFormatException。
     */
    private BigDecimal decimal(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    
    /**
     * 还原常见 HTML 转义。未覆盖的实体保持原样，不因此失败。
     */
    private String html(String value) {
        return value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
    }

    
    /**
     * 催化研究入参。theme 与 fundCode 至少应提供一个，但该约束由 resolve 执行。
     * topN 和 lookbackDays 的范围注解不会在紧凑构造器里执行；空值分别收成 10 和 45。
     */
    public record Input(String theme, String fundCode, @Min(1) @Max(20) Integer topN,
            @Min(7) @Max(180) Integer lookbackDays) {
        /**
         * 为空的条数和回看天数填上默认值。越界的非空值会原样保留，直到外部校验器介入。
         */
        public Input {
            topN = topN == null ? 10 : topN;
            lookbackDays = lookbackDays == null ? 45 : lookbackDays;
        }
    }

    
    /**
     * 一只重仓股及其在估算篮子中的权重百分比。权重不是基金公司会计账簿中的精确仓位。
     */
    public record Holding(String stockCode, String stockName, BigDecimal weightPercent) {}

    /**
     * 一只持仓对应的行业标签、产业链位置和主营摘要。资料缺失时摘要会写明暂不可用，而不是留空冒充已核对。
     */
    public record IndustryExposure(String stockCode, String stockName, BigDecimal weightPercent, String chainRole,
            List<String> industryTags, String mainBusinessSummary) {}

    /**
     * 公告标题的规则化方向。中性表示标题没有命中正负词，不等于事件对净值没有影响。
     */
    public enum EventDirection { POSITIVE, NEGATIVE, NEUTRAL }

    /**
     * 一条可定位到公告页面的公司事件。sourceUrl 是引用位置；未核实传言不会生成该记录。
     */
    public record VerifiedEvent(String eventId, String stockCode, String stockName, BigDecimal holdingWeight,
            String title, String category, EventDirection direction, LocalDate publishedDate, String sourceLevel,
            String sourceName, String sourceUrl) {}

    /**
     * 正负事件覆盖的去重持仓权重。overallBias 是规则化结论，不是价格预测。
     */
    public record ImpactAssessment(BigDecimal positiveAffectedWeight, BigDecimal negativeAffectedWeight,
            long positiveEventCount, long negativeEventCount, long neutralEventCount, String overallBias,
            String methodology) {}

    /**
     * 返回给模型的催化研究结果。limitations 必须保留，用来说明篮子估算和规则分类的边界。
     */
    public record CatalystResearch(String fundName, String fundCode, String theme, LocalDate holdingsAsOf,
            String holdingsDisclosureType, BigDecimal disclosedHoldingsCoveragePercent, long holdingsStalenessDays,
            List<Holding> holdings, List<IndustryExposure> industryChainExposure, List<VerifiedEvent> verifiedEvents,
            ImpactAssessment impactAssessment, Instant searchedAt, String holdingsDataMode, String underlyingEtfCode,
            String underlyingEtfName, List<String> dataSources, List<String> limitations) {}

    /**
     * 解析后的基金代码、主题和名称。名称为 null 表示名称查询失败，持仓步骤仍可继续。
     */
    private record ResolvedFund(String code, String theme, String name) {}

    /**
     * 主题搜索得到的候选基金及排序分。分数只用于挑选，不会出现在最终结果里。
     */
    private record FundCandidate(String code, String name, int score) {}

    /**
     * 一次持仓读取的内部结果。holdingsDataMode 用来区分日频篮子和披露持仓，避免把两者写成同一种证据。
     */
    private record Portfolio(String fundName, String fundCode, LocalDate disclosureDate, String disclosureType,
            BigDecimal coveragePercent, List<Holding> holdings, String underlyingEtfCode, String underlyingEtfName,
            String holdingsDataMode) {}

    /**
     * 申赎清单中的一只成分证券。数量不是正数的行不会被解析进来。
     */
    record PcfComponent(String stockCode, String stockName, long quantity) {}

    /**
     * 带有最新行情市值的成分证券。没有正价格的成分不会进入该记录。
     */
    private record ValuedComponent(PcfComponent component, BigDecimal marketValue) {}
}
