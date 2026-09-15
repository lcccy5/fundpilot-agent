package com.jijing.fund.agent.orchestration;

import java.util.Locale;

/** Stable files inside one logical per-fund memory folder. */
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

    static FundMemoryCategory fromTool(String toolName) {
        String name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (name.contains("profile")) return PROFILE;
        if (name.contains("realtime") || name.contains("quote")) return REALTIME;
        if (name.contains("metric") || name.contains("comparison")) return METRICS;
        if (name.contains("nav")) return NAV;
        if (name.contains("holding") || name.contains("position")) return HOLDINGS;
        if (name.contains("sector") || name.contains("event") || name.contains("catalyst")
                || name.contains("impact") || name.contains("industry")) return MARKET_SIGNALS;
        if (name.contains("document") || name.contains("report")) return DOCUMENTS;
        if (name.contains("personal") || name.contains("portfolio")) return USER_CONTEXT;
        return OTHER;
    }
}
