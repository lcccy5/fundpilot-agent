package com.jijing.fund.infrastructure.cache;

import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.FundProfile;
import com.jijing.fund.domain.model.NavPoint;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 进程内基金查询缓存，仅在 Redis 关闭时启用。没有连接失败。
 * 档案没有过期时间。净值历史写入 30 分钟后读取视为过期并删除，返回空。
 * 重复写入覆盖同一键。过期判断使用 {@link Instant#now()}，测试不能注入时钟。
 */
@Component
@ConditionalOnProperty(prefix = "fund.cache", name = "redis-enabled", havingValue = "false")
public class InMemoryFundQueryCache implements FundQueryCache {
    private final Map<String, FundProfile> profiles = new ConcurrentHashMap<>();
    private final Map<String, HistoryEntry> history = new ConcurrentHashMap<>();
    private static final Duration HISTORY_TTL = Duration.ofMinutes(30);

    /** 未写入或已被逐出时为空。 */
    @Override
    public Optional<FundProfile> getProfile(FundCode code) {
        return Optional.ofNullable(profiles.get(code.value()));
    }

    /** 以基金代码覆盖档案。 */
    @Override
    public void putProfile(FundProfile profile) {
        profiles.put(profile.code().value(), profile);
    }

    /** 过期条目在返回前删除。并发删除用值匹配，避免清掉刚写入的新条目。 */
    @Override
    public Optional<List<NavPoint>> getHistory(FundCode code, LocalDate startDate, LocalDate endDate) {
        String key = code.value() + "|" + startDate + "|" + endDate;
        HistoryEntry entry = history.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.cachedAt().plus(HISTORY_TTL).isBefore(Instant.now())) {
            history.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.points());
    }

    /** 历史列表复制后保存，调用方之后修改原列表不会影响缓存。 */
    @Override
    public void putHistory(FundCode code, LocalDate startDate, LocalDate endDate, List<NavPoint> points) {
        history.put(code.value() + "|" + startDate + "|" + endDate, new HistoryEntry(List.copyOf(points), Instant.now()));
    }

    /** 档案和该代码的全部历史区间一起去掉。 */
    @Override
    public void evict(FundCode code) {
        profiles.remove(code.value());
        history.keySet().removeIf(key -> key.startsWith(code.value() + "|"));
    }

    /** 带写入时刻的历史快照，用于 30 分钟过期。 */
    private record HistoryEntry(List<NavPoint> points, Instant cachedAt) {
        /**
         * 记录组件原样保存。列表已由调用方复制。
         */
        private HistoryEntry {
        }
    }
}
