package com.jijing.fund.agent.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 按用户原话选择本次可以暴露给模型的工具。匹配失败时返回空数组，不抛出业务异常。
 * message 为 null 时在转小写处抛出 NullPointerException。
 */
public final class FundToolRouter {
    private final FundProfileTool profile;
    private final FundNavTool nav;
    private final FundMetricsTool metrics;
    private final FundComparisonTool comparison;
    private final FundDocumentSearchTool documents;
    private final FundRealtimeQuoteTool realtime;
    private final SectorOutlookTool sector;
    private final FundCatalystResearchTool catalyst;
    private final PersonalFundTool personal;

    /**
     * 只装配档案、净值、指标、比较和文档工具。实时、板块、催化和个人工具保持为空，对应问题不会暴露它们。
     */
    public FundToolRouter(FundProfileTool profile, FundNavTool nav, FundMetricsTool metrics,
            FundComparisonTool comparison, FundDocumentSearchTool documents) {
        this(profile, nav, metrics, comparison, documents, null, null, null);
    }

    /**
     * 在基础工具上增加实时行情。未提供的板块、催化和个人工具保持为空。
     */
    public FundToolRouter(FundProfileTool profile, FundNavTool nav, FundMetricsTool metrics,
            FundComparisonTool comparison, FundDocumentSearchTool documents, FundRealtimeQuoteTool realtime) {
        this(profile, nav, metrics, comparison, documents, realtime, null, null);
    }

    /**
     * 在实时行情之外增加板块情景工具。催化和个人工具保持为空。
     */
    public FundToolRouter(FundProfileTool profile, FundNavTool nav, FundMetricsTool metrics,
            FundComparisonTool comparison, FundDocumentSearchTool documents, FundRealtimeQuoteTool realtime,
            SectorOutlookTool sector) {
        this(profile, nav, metrics, comparison, documents, realtime, sector, null);
    }

    /**
     * 增加催化研究工具。个人工具保持为空，自选和持仓问题不会暴露个人数据工具。
     */
    public FundToolRouter(FundProfileTool profile, FundNavTool nav, FundMetricsTool metrics,
            FundComparisonTool comparison, FundDocumentSearchTool documents, FundRealtimeQuoteTool realtime,
            SectorOutlookTool sector, FundCatalystResearchTool catalyst) {
        this(profile, nav, metrics, comparison, documents, realtime, sector, catalyst, null);
    }

    /**
     * 装配全部可选工具。任一后置工具为 null 时，对应分支直接跳过，不抛出异常。
     */
    public FundToolRouter(FundProfileTool profile, FundNavTool nav, FundMetricsTool metrics,
            FundComparisonTool comparison, FundDocumentSearchTool documents, FundRealtimeQuoteTool realtime,
            SectorOutlookTool sector, FundCatalystResearchTool catalyst, PersonalFundTool personal) {
        this.profile = profile;
        this.nav = nav;
        this.metrics = metrics;
        this.comparison = comparison;
        this.documents = documents;
        this.realtime = realtime;
        this.sector = sector;
        this.catalyst = catalyst;
        this.personal = personal;
    }

    /**
     * 按关键词决定工具集合。催化问题在未要求指标或实时行情联用时只返回催化工具，避免重复调用挤占审计步数。
     * 没有命中任何工具时返回空数组。message 为 null 时抛出 NullPointerException。
     */
    public Object[] toolsFor(String message) {
        String text = message.toLowerCase(Locale.ROOT);
        List<Object> tools = new ArrayList<>();
        if (personal != null && containsAny(text, "我的", "自选", "持仓", "成本", "我赚", "我的组合")) {
            tools.add(personal);
        }
        boolean documentQuestion = containsAny(text, "公告", "招募说明书", "季报", "年报", "半年报", "定期报告",
                "投资策略", "投资范围", "业绩比较基准", "基金经理", "如何解释", "披露", "原文", "条款");
        boolean realtimeQuestion = containsAny(text, "实时", "当前", "今天", "今日", "盘中", "估值");
        boolean hasFundCode = text.matches(".*\\d{6}.*");
        boolean futureQuestion = containsAny(text, "预测", "未来", "后市", "前景", "一个月", "下个月", "走势", "怎么看");
        boolean catalystQuestion = containsAny(text, "利好", "利空", "催化", "消息", "新闻", "公告", "产业链",
                "重仓股", "持仓", "为什么跌", "下跌原因", "上涨原因", "风险事件");
        boolean explicitCombinedAnalysis = (documentQuestion && containsAny(text, "指标", "收益", "回撤", "波动", "夏普", "风险"))
                || (documentQuestion && realtimeQuestion);
        boolean sectorSubject = containsAny(text, "板块", "行业", "主题");
        // 明确的板块问题始终带上情景工具。若还要求“预测”等词，像“机器人板块最近咋样”就不会暴露任何工具，模型会编造调用。
        boolean sectorQuestion = (sectorSubject && !hasFundCode)
                || (futureQuestion && !hasFundCode && !text.contains("基金"));
        // 催化研究工具内部已完成持仓、产业链、公告和影响评估四步；
        // 这类问题不再同时暴露板块/文档工具，避免重复调用挤占审计步数。
        if (catalystQuestion && !explicitCombinedAnalysis && catalyst != null) {
            return new Object[]{catalyst};
        }
        if (sectorQuestion && sector != null) {
            tools.add(sector);
        }
        if (documentQuestion && documents != null) {
            tools.add(documents);
        }
        if (realtime != null && realtimeQuestion) {
            tools.add(realtime);
        }
        if (containsAny(text, "比较", "对比", "哪个", "排名", "vs")) {
            tools.add(comparison);
        } else if (containsAny(text, "收益", "回撤", "波动", "夏普", "风险", "涨跌幅", "指标") && !realtimeQuestion) {
            tools.add(metrics);
        } else if (containsAny(text, "净值", "走势", "历史")) {
            tools.add(nav);
        }
        if (hasFundCode && tools.isEmpty()) {
            tools.add(profile);
            tools.add(metrics);
        } else if (hasFundCode || (!tools.isEmpty() && !sectorQuestion)) {
            tools.add(profile);
        }
        return tools.toArray();
    }

    /**
     * 判断原文是否包含任一关键词。关键词列表为空时返回 false，不抛出异常。
     */
    private boolean containsAny(String text, String... values) {
        return Arrays.stream(values).anyMatch(text::contains);
    }
}
