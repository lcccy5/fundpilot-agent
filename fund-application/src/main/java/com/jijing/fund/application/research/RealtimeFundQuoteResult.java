package com.jijing.fund.application.research;

import com.jijing.fund.domain.research.model.SourcedValue;
import java.util.List;

public record RealtimeFundQuoteResult(RealtimeFundQuoteStatus status, SourcedValue<RealtimeFundQuoteView> quote,
                                      List<String> limitations) {
    public RealtimeFundQuoteResult {
        if (status == null) throw new IllegalArgumentException("status is required");
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        if ((status == RealtimeFundQuoteStatus.AVAILABLE || status == RealtimeFundQuoteStatus.STALE) && quote == null) {
            throw new IllegalArgumentException("quote is required for available data");
        }
        if ((status == RealtimeFundQuoteStatus.NO_EXCHANGE_PROXY || status == RealtimeFundQuoteStatus.DATA_NOT_READY) && quote != null) {
            throw new IllegalArgumentException("quote must be absent when data is unavailable");
        }
    }

    public static RealtimeFundQuoteResult unavailable(RealtimeFundQuoteStatus status, String limitation) {
        return new RealtimeFundQuoteResult(status, null, List.of(limitation));
    }
}
