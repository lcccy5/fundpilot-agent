package com.jijing.fund.application.research;

import com.jijing.fund.domain.research.model.SourcedValue;
import java.util.List;

/**
 * 一次代理行情查询的结果。可用和过期必须带行情，没有代理标的或数据未就绪时行情必须为空。
 * limitations 说明不能把代理价格当成基金净值等限制；空列表会被收成不可变空列表。
 */
public record RealtimeFundQuoteResult(RealtimeFundQuoteStatus status, SourcedValue<RealtimeFundQuoteView> quote,
        List<String> limitations) {
    /**
     * 核对状态和行情是否匹配。状态为空时抛出参数异常。可用或过期却没有行情、没有代理或未就绪却带了行情时抛出参数异常。
     * 限制说明为空时改成空列表。
     */
    public RealtimeFundQuoteResult {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        if ((status == RealtimeFundQuoteStatus.AVAILABLE || status == RealtimeFundQuoteStatus.STALE) && quote == null) {
            throw new IllegalArgumentException("quote is required for available data");
        }
        if ((status == RealtimeFundQuoteStatus.NO_EXCHANGE_PROXY || status == RealtimeFundQuoteStatus.DATA_NOT_READY) && quote != null) {
            throw new IllegalArgumentException("quote must be absent when data is unavailable");
        }
    }

    /**
     * 构造一条没有行情的结果，并把单条限制说明放进列表。状态若是可用或过期，会因为缺少行情而抛出参数异常。
     * 限制说明为空时仍会放进列表，不会被删掉。
     */
    public static RealtimeFundQuoteResult unavailable(RealtimeFundQuoteStatus status, String limitation) {
        return new RealtimeFundQuoteResult(status, null, List.of(limitation));
    }
}
