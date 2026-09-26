package com.jijing.fund.agent.orchestration;

import java.util.Locale;

/**
 * 一只基金记忆目录中的稳定分区。
 * 工具名无法归类时落入 OTHER，检索不会因此扩大到全部分区。
 * 未知话题在跟读时被忽略，不把计划失败或路由失败写成新的记忆类别。
 */
enum FundMemoryCategory {
    PROFILE,
    REALTIME,
    METRICS,
    NAV,
    HOLDINGS,
    MARKET_SIGNALS,
    DOCUMENTS,
    USER_CONTEXT,
    OTHER;

    /**
     * 按工具名选择记忆分区。
     * 工具名为 null 时归入 OTHER。名称同时命中多类关键词时采用先匹配到的分区，避免一次观察写入多个目录。
     */
    static FundMemoryCategory fromTool(String toolName) {
        String name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (name.contains("profile")) {
            return PROFILE;
        }
        if (name.contains("realtime") || name.contains("quote")) {
            return REALTIME;
        }
        if (name.contains("metric") || name.contains("comparison")) {
            return METRICS;
        }
        if (name.contains("nav")) {
            return NAV;
        }
        if (name.contains("holding") || name.contains("position")) {
            return HOLDINGS;
        }
        if (name.contains("sector") || name.contains("event") || name.contains("catalyst")
                || name.contains("impact") || name.contains("industry")) {
            return MARKET_SIGNALS;
        }
        if (name.contains("document") || name.contains("report")) {
            return DOCUMENTS;
        }
        if (name.contains("personal") || name.contains("portfolio")) {
            return USER_CONTEXT;
        }
        return OTHER;
    }
}
