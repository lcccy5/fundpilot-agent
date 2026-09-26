package com.jijing.fund.infrastructure.provider;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.FundProfile;
import com.jijing.fund.domain.model.NavPoint;
import com.jijing.fund.domain.model.NavStatus;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地固定样本，供未配置真实供应商时启动。没有网络调用，因此不存在超时、空响应或连接失败。
 * 未知代码的档案返回空，净值返回空列表。重复调用返回同一批内存数据。
 */
@Component
@ConditionalOnProperty(prefix = "fund.provider", name = "type", havingValue = "mock", matchIfMissing = true)
public class MockExternalFundDataProvider implements ExternalFundDataProvider {
    private static final Instant COLLECTED_AT = Instant.parse("2026-01-31T07:00:00Z");
    private static final Map<String, FundProfile> PROFILES = Map.of(
            "000001", profile("000001", "华夏成长混合", "混合型", "华夏基金", "王明"),
            "110022", profile("110022", "易方达消费行业股票", "股票型", "易方达基金", "张坤"));

    /** 样本里没有的代码返回空，而不是抛出数据源异常。 */
    @Override
    public Optional<FundProfile> fetchProfile(FundCode code) {
        return Optional.ofNullable(PROFILES.get(code.value()));
    }

    /** 未知代码直接空列表；已知代码再按查询区间过滤五条固定净值。 */
    @Override
    public List<NavPoint> fetchNavHistory(FundCode code, LocalDate start, LocalDate end) {
        if (!PROFILES.containsKey(code.value())) {
            return List.of();
        }
        return List.of(point(code, "2026-01-02", "1.0120"), point(code, "2026-01-05", "1.0180"),
                point(code, "2026-01-06", "1.0090"), point(code, "2026-01-07", "1.0240"),
                point(code, "2026-01-08", "1.0300")).stream()
                .filter(item -> !item.navDate().isBefore(start) && !item.navDate().isAfter(end)).toList();
    }

    /** 领域对象上的来源标记，固定为 mock。 */
    @Override
    public String sourceName() {
        return "mock";
    }

    /** 两条演示档案共用同一个采集时间，避免测试依赖时钟。 */
    private static FundProfile profile(String code, String name, String type, String company, String manager) {
        return new FundProfile(new FundCode(code), name, type, company, manager, LocalDate.of(2001, 12, 18),
                "mock", COLLECTED_AT, COLLECTED_AT);
    }

    /** 单位净值与累计净值使用同一数字，状态固定为已确认。 */
    private static NavPoint point(FundCode code, String date, String nav) {
        return new NavPoint(code, LocalDate.parse(date), new BigDecimal(nav), new BigDecimal(nav), null,
                NavStatus.CONFIRMED, "mock", COLLECTED_AT, COLLECTED_AT);
    }
}
