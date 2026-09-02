package com.jijing.fund.agent.tool;

import com.fasterxml.jackson.databind.*;
import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/** 基于代表性场内 ETF 的真实行情，对主题板块做可解释的情景展望。 */
public final class SectorOutlookTool {
    public static final String NAME="analyze_sector_outlook";
    private static final String UA="Mozilla/5.0 FundPilot/1.0";
    private final RestClient quote=RestClient.builder().baseUrl("https://qt.gtimg.cn").build();
    private final RestClient kline=RestClient.builder().baseUrl("https://web.ifzq.gtimg.cn").build();
    private final RestClient search=RestClient.builder().baseUrl("https://fundsuggest.eastmoney.com").build();
    private final ObjectMapper mapper=new ObjectMapper();

    @Tool(name=NAME,description="分析中国市场行业或主题板块未来约一个月的可能情景。自动选择代表ETF，查询实时价格与近90个交易日日线，计算20/60日涨跌、年化波动、最大回撤、均线和量能，返回基准/乐观/悲观情景及触发条件。不是确定性涨跌预测。")
    
    /** 执行该 Agent 运行时组件中的 analyze 操作。 */
    public FundToolEnvelope<SectorOutlook> analyze(@Valid Input input,ToolContext context){
        AgentExecutionTrace trace=FundToolSupport.trace(context);var call=trace.begin(NAME,input);
        try{Representative rep=resolve(input.theme());MarketQuote live=live(rep.symbol());List<Bar> bars=history(rep.symbol());if(bars.size()<61)throw new IllegalArgumentException("该板块历史行情样本不足");
            List<Bar> recent=bars.subList(bars.size()-60,bars.size());BigDecimal r20=change(bars.get(bars.size()-21).close(),bars.get(bars.size()-1).close()),r60=change(recent.get(0).close(),recent.get(59).close());BigDecimal ma20=average(bars.subList(bars.size()-20,bars.size()).stream().map(Bar::close).toList());BigDecimal volatility=volatility(recent);BigDecimal drawdown=maxDrawdown(recent);BigDecimal volumeRatio=volumeRatio(bars);String stance=stance(live.current(),ma20,r20);
            var scenarios=scenarios(stance,ma20,live.current());var result=new SectorOutlook(input.theme(),rep.name(),rep.code(),live.current(),live.changePercent(),live.quoteTime(),r20,r60,volatility,drawdown,ma20,volumeRatio,stance,scenarios,"eastmoney-fund-search+tencent-qt+tencent-kline","这是基于动态搜索到的代表ETF/LOF历史行情的条件化情景分析，不是对板块未来收益的保证或确定性预测");
            var evidence=new EvidenceReference("ev-sector-"+UUID.randomUUID(),"FUND_SECTOR_OUTLOOK",rep.code(),bars.get(bars.size()-60).date(),bars.get(bars.size()-1).date(),"DYNAMIC_REPRESENTATIVE_EXCHANGE_FUND","eastmoney-fund-search+tencent-qt+tencent-kline",live.version(),"sector-outlook-v1",Instant.now());trace.success(call,evidence,result);return FundToolEnvelope.success(NAME,result,evidence,List.of("SCENARIO_ANALYSIS_NOT_CERTAIN_FORECAST","REPRESENTATIVE_FUND_MAY_NOT_FULLY_TRACK_THEME"));
        }catch(IllegalArgumentException ex){trace.failure(call,"SECTOR_UNSUPPORTED");return new FundToolEnvelope<>(NAME,"sector-outlook-v1",ToolResultStatus.USER_CORRECTABLE,null,List.of(),List.of(),"SECTOR_UNSUPPORTED",ex.getMessage());}
        catch(RuntimeException ex){trace.failure(call,"SECTOR_DATA_UNAVAILABLE");return new FundToolEnvelope<>(NAME,"sector-outlook-v1",ToolResultStatus.DATA_NOT_READY,null,List.of(),List.of(),"SECTOR_DATA_UNAVAILABLE","板块行情数据源暂不可用");}
    }
    
    /** 构造后续 Agent 处理所需的 resolve 值。 */
    private Representative resolve(String theme){
        String keyword=theme.replaceAll("(?i)(板块|行业|主题|未来|后市|走势|一个月|预测|分析|情况|怎么样|怎么看|\\s)","");if(keyword.isBlank())keyword=theme.trim();
        ArrayDeque<String> queries=new ArrayDeque<>(List.of(keyword+"ETF",keyword));Set<String>seen=new HashSet<>();RuntimeException failure=null;int searched=0;
        while(!queries.isEmpty()&&searched++<8){String query=queries.removeFirst();if(!seen.add(query))continue;try{
            String body=search.get().uri(u->u.path("/FundSearch/api/FundSearchAPI.ashx").queryParam("m",1).queryParam("key",query).build()).header(HttpHeaders.USER_AGENT,UA).header(HttpHeaders.REFERER,"https://fund.eastmoney.com/").retrieve().body(String.class);JsonNode rows=mapper.readTree(body).path("Datas");List<ScoredRepresentative>candidates=new ArrayList<>();String focus=query.replace("ETF","");
            for(JsonNode row:rows){for(JsonNode tag:row.path("ZTJJInfo")){String category=tag.path("TTYPENAME").asText();if(!category.isBlank()&&!category.equals(keyword)){queries.addLast(category+"ETF");queries.addLast(category);}}String code=row.path("CODE").asText(),name=row.path("NAME").asText();if(!code.matches("[15]\\d{5}"))continue;int score=(name.contains(focus)?100:0)+(name.toUpperCase(Locale.ROOT).contains("ETF")?50:0)+(name.toUpperCase(Locale.ROOT).contains("LOF")?30:0);candidates.add(new ScoredRepresentative(new Representative(name,code,(code.startsWith("5")?"sh":"sz")+code),score));}
            candidates.sort(Comparator.comparingInt(ScoredRepresentative::score).reversed());for(ScoredRepresentative candidate:candidates.stream().limit(5).toList()){try{if(history(candidate.representative().symbol()).size()>=61)return candidate.representative();}catch(RuntimeException ignored){}}
        }catch(Exception ex){failure=ex instanceof RuntimeException r?r:new IllegalStateException(ex);}}
        String t=keyword.toLowerCase(Locale.ROOT);if(t.contains("机器人")||t.contains("人形"))return new Representative("机器人ETF","562500","sh562500");if(t.contains("人工智能")||t.matches(".*\\bai\\b.*"))return new Representative("人工智能ETF","159819","sz159819");if(t.contains("半导体")||t.contains("芯片"))return new Representative("半导体ETF","512480","sh512480");if(t.contains("证券")||t.contains("券商"))return new Representative("证券ETF","512880","sh512880");if(t.contains("新能源")||t.contains("光伏")||t.contains("储能"))return new Representative("新能源ETF","516160","sh516160");if(failure!=null)throw new IllegalArgumentException("主题搜索服务暂不可用，请稍后重试");throw new IllegalArgumentException("没有找到“"+keyword+"”对应且具备完整公开行情的场内ETF或LOF，请换一个更具体的行业/主题名称");
    }
    
    /** 执行该 Agent 运行时组件中的 live 操作。 */
    private MarketQuote live(String symbol){String body=quote.get().uri(u->u.queryParam("q",symbol).build()).header(HttpHeaders.USER_AGENT,UA).header(HttpHeaders.REFERER,"https://gu.qq.com/").retrieve().body(String.class);int a=body==null?-1:body.indexOf('"'),b=body==null?-1:body.lastIndexOf('"');if(a<0||b<=a)throw new IllegalArgumentException("实时行情响应无法解析");String[]f=body.substring(a+1,b).split("~",-1);if(f.length<=32)throw new IllegalArgumentException("实时行情字段不完整");Instant time;try{time=LocalDateTime.parse(f[30],DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).atZone(ZoneId.of("Asia/Shanghai")).toInstant();}catch(Exception ex){time=Instant.now();}return new MarketQuote(decimal(f[3]),decimal(f[32]),time,f[30]);}
    
    /** 执行该 Agent 运行时组件中的 history 操作。 */
    private List<Bar> history(String symbol){String body=kline.get().uri(u->u.path("/appstock/app/fqkline/get").queryParam("param",symbol+",day,,,90,qfq").build()).header(HttpHeaders.USER_AGENT,UA).header(HttpHeaders.REFERER,"https://gu.qq.com/").retrieve().body(String.class);try{JsonNode rows=mapper.readTree(body).path("data").path(symbol).path("day");List<Bar>out=new ArrayList<>();for(JsonNode r:rows)out.add(new Bar(LocalDate.parse(r.get(0).asText()),decimal(r.get(2).asText()),decimal(r.get(5).asText())));return out;}catch(Exception ex){throw new IllegalArgumentException("历史行情响应无法解析");}}
    
    /** 执行该 Agent 运行时组件中的 change 操作。 */
    private BigDecimal change(BigDecimal from,BigDecimal to){return to.divide(from,8,RoundingMode.HALF_UP).subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2,RoundingMode.HALF_UP);}
    
    /** 执行该 Agent 运行时组件中的 average 操作。 */
    private BigDecimal average(List<BigDecimal>v){return v.stream().reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(v.size()),6,RoundingMode.HALF_UP);}
    
    /** 执行该 Agent 运行时组件中的 volatility 操作。 */
    private BigDecimal volatility(List<Bar>b){List<Double>r=new ArrayList<>();for(int i=1;i<b.size();i++)r.add(Math.log(b.get(i).close().doubleValue()/b.get(i-1).close().doubleValue()));double mean=r.stream().mapToDouble(x->x).average().orElse(0),variance=r.stream().mapToDouble(x->(x-mean)*(x-mean)).sum()/Math.max(1,r.size()-1);return BigDecimal.valueOf(Math.sqrt(variance*252)*100).setScale(2,RoundingMode.HALF_UP);}
    
    /** 执行该 Agent 运行时组件中的 maxDrawdown 操作。 */
    private BigDecimal maxDrawdown(List<Bar>b){double peak=0,worst=0;for(Bar x:b){double p=x.close().doubleValue();peak=Math.max(peak,p);worst=Math.min(worst,p/peak-1);}return BigDecimal.valueOf(worst*100).setScale(2,RoundingMode.HALF_UP);}
    
    /** 执行该 Agent 运行时组件中的 volumeRatio 操作。 */
    private BigDecimal volumeRatio(List<Bar>b){BigDecimal last5=average(b.subList(b.size()-5,b.size()).stream().map(Bar::volume).toList()),previous20=average(b.subList(b.size()-25,b.size()-5).stream().map(Bar::volume).toList());return last5.divide(previous20,2,RoundingMode.HALF_UP);}
    
    /** 执行该 Agent 运行时组件中的 stance 操作。 */
    private String stance(BigDecimal price,BigDecimal ma20,BigDecimal r20){if(price.compareTo(ma20)>0&&r20.signum()>0)return "偏强但需防范主题波动";if(price.compareTo(ma20)<0&&r20.signum()<0)return "偏弱，等待趋势修复";return "震荡，方向尚未形成共振";}
    
    /** 执行该 Agent 运行时组件中的 scenarios 操作。 */
    private List<Scenario> scenarios(String stance,BigDecimal ma20,BigDecimal price){return List.of(new Scenario("基准情景",stance,"价格围绕20日均线运行，量能没有显著恶化"),new Scenario("乐观情景","趋势延续或转强","放量站稳20日均线并出现产业或政策催化"),new Scenario("悲观情景","回撤扩大","跌破近期支撑且成交放大，市场风险偏好下降"));}
    
    /** 执行该 Agent 运行时组件中的 decimal 操作。 */
    private BigDecimal decimal(String s){return new BigDecimal(s);}
    
    /** 在 Agent 运行时边界间传递 Input 数据的不可变值对象。 */
    public record Input(@NotBlank String theme,Integer horizonDays){}
    
    /** 在 Agent 运行时边界间传递 Scenario 数据的不可变值对象。 */
    public record Scenario(String name,String outlook,String trigger){}
    
    /** 在 Agent 运行时边界间传递 SectorOutlook 数据的不可变值对象。 */
    public record SectorOutlook(String theme,String representativeEtfName,String representativeEtfCode,BigDecimal currentPrice,BigDecimal todayChangePercent,Instant quoteTime,BigDecimal return20TradingDays,BigDecimal return60TradingDays,BigDecimal annualizedVolatility60Days,BigDecimal maxDrawdown60Days,BigDecimal movingAverage20Days,BigDecimal recentVolumeRatio,String currentStance,List<Scenario> scenarios,String dataSource,String disclaimer){}
    
    /** 在 Agent 运行时边界间传递 Representative 数据的不可变值对象。 */
    private record Representative(String name,String code,String symbol){} 
    /** 在 Agent 运行时边界间传递 ScoredRepresentative 数据的不可变值对象。 */
    private record ScoredRepresentative(Representative representative,int score){} 
    /** 在 Agent 运行时边界间传递 MarketQuote 数据的不可变值对象。 */
    private record MarketQuote(BigDecimal current,BigDecimal changePercent,Instant quoteTime,String version){} 
    /** 在 Agent 运行时边界间传递 Bar 数据的不可变值对象。 */
    private record Bar(LocalDate date,BigDecimal close,BigDecimal volume){}
}
