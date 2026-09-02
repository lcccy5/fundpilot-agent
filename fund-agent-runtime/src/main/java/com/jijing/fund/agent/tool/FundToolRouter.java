package com.jijing.fund.agent.tool;

import java.util.*;

/** 实现 FundToolRouter 所代表的 Agent 运行时职责。 */
public final class FundToolRouter {
    private final FundProfileTool profile;private final FundNavTool nav;private final FundMetricsTool metrics;private final FundComparisonTool comparison;private final FundDocumentSearchTool documents;private final FundRealtimeQuoteTool realtime;private final SectorOutlookTool sector;private final FundCatalystResearchTool catalyst;private final PersonalFundTool personal;
    
    /** 执行该 Agent 运行时组件中的 FundToolRouter 操作。 */
    public FundToolRouter(FundProfileTool profile,FundNavTool nav,FundMetricsTool metrics,FundComparisonTool comparison,FundDocumentSearchTool documents){this(profile,nav,metrics,comparison,documents,null,null,null);}
    
    /** 执行该 Agent 运行时组件中的 FundToolRouter 操作。 */
    public FundToolRouter(FundProfileTool profile,FundNavTool nav,FundMetricsTool metrics,FundComparisonTool comparison,FundDocumentSearchTool documents,FundRealtimeQuoteTool realtime){this(profile,nav,metrics,comparison,documents,realtime,null,null);}
    
    /** 执行该 Agent 运行时组件中的 FundToolRouter 操作。 */
    public FundToolRouter(FundProfileTool profile,FundNavTool nav,FundMetricsTool metrics,FundComparisonTool comparison,FundDocumentSearchTool documents,FundRealtimeQuoteTool realtime,SectorOutlookTool sector){this(profile,nav,metrics,comparison,documents,realtime,sector,null);}
    
    /** 执行该 Agent 运行时组件中的 FundToolRouter 操作。 */
    public FundToolRouter(FundProfileTool profile,FundNavTool nav,FundMetricsTool metrics,FundComparisonTool comparison,FundDocumentSearchTool documents,FundRealtimeQuoteTool realtime,SectorOutlookTool sector,FundCatalystResearchTool catalyst){this(profile,nav,metrics,comparison,documents,realtime,sector,catalyst,null);}
    
    /** 执行该 Agent 运行时组件中的 FundToolRouter 操作。 */
    public FundToolRouter(FundProfileTool profile,FundNavTool nav,FundMetricsTool metrics,FundComparisonTool comparison,FundDocumentSearchTool documents,FundRealtimeQuoteTool realtime,SectorOutlookTool sector,FundCatalystResearchTool catalyst,PersonalFundTool personal){this.profile=profile;this.nav=nav;this.metrics=metrics;this.comparison=comparison;this.documents=documents;this.realtime=realtime;this.sector=sector;this.catalyst=catalyst;this.personal=personal;}
    
    /** 执行该 Agent 运行时组件中的 toolsFor 操作。 */
    public Object[] toolsFor(String message){String text=message.toLowerCase(Locale.ROOT);List<Object>tools=new ArrayList<>();if(personal!=null&&containsAny(text,"我的","自选","持仓","成本","我赚","我的组合"))tools.add(personal);boolean documentQuestion=containsAny(text,"公告","招募说明书","季报","年报","半年报","定期报告","投资策略","投资范围","业绩比较基准","基金经理","如何解释","披露","原文","条款");boolean realtimeQuestion=containsAny(text,"实时","当前","今天","今日","盘中","估值");boolean hasFundCode=text.matches(".*\\d{6}.*");boolean futureQuestion=containsAny(text,"预测","未来","后市","前景","一个月","下个月","走势","怎么看");
        boolean catalystQuestion=containsAny(text,"利好","利空","催化","消息","新闻","公告","产业链","重仓股","持仓","为什么跌","下跌原因","上涨原因","风险事件");
        boolean explicitCombinedAnalysis=(documentQuestion&&containsAny(text,"指标","收益","回撤","波动","夏普","风险"))
                ||(documentQuestion&&realtimeQuestion);
        boolean sectorQuestion=futureQuestion&&(containsAny(text,"板块","行业","主题")||(!hasFundCode&&!text.contains("基金")));
        // 催化研究工具内部已完成持仓、产业链、公告和影响评估四步；
        // 这类问题不再同时暴露板块/文档工具，避免重复调用挤占审计步数。
        if(catalystQuestion&&!explicitCombinedAnalysis&&catalyst!=null)return new Object[]{catalyst};
        if(sectorQuestion&&sector!=null)tools.add(sector);
        if(documentQuestion&&documents!=null)tools.add(documents);
        if(realtime!=null&&realtimeQuestion)tools.add(realtime);
        if(containsAny(text,"比较","对比","哪个","排名","vs"))tools.add(comparison);
        else if(containsAny(text,"收益","回撤","波动","夏普","风险","涨跌幅","指标")&&!realtimeQuestion)tools.add(metrics);
        else if(containsAny(text,"净值","走势","历史"))tools.add(nav);
        if(hasFundCode&&tools.isEmpty()){tools.add(profile);tools.add(metrics);}else if(hasFundCode||(!tools.isEmpty()&&!sectorQuestion))tools.add(profile);
        return tools.toArray();
    }
    
    /** 执行该 Agent 运行时组件中的 containsAny 操作。 */
    private boolean containsAny(String text,String...values){return Arrays.stream(values).anyMatch(text::contains);}
}
