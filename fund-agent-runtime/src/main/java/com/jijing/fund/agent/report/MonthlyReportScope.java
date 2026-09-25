package com.jijing.fund.agent.report;

import java.util.List;

/** The month and holdings or watchlist a monthly report is allowed to use. */
public record MonthlyReportScope(String month, String kind, String label, List<String> fundCodes) {
    public MonthlyReportScope {
        fundCodes = fundCodes == null ? List.of() : List.copyOf(fundCodes);
    }
}
