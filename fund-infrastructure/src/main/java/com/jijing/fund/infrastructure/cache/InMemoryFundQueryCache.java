package com.jijing.fund.infrastructure.cache;

import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.model.*;
import java.time.LocalDate;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="fund.cache",name="redis-enabled",havingValue="false")
public class InMemoryFundQueryCache implements FundQueryCache {
    private final Map<String,FundProfile> profiles=new ConcurrentHashMap<>();
    private final Map<String,HistoryEntry> history=new ConcurrentHashMap<>();
    private static final Duration HISTORY_TTL=Duration.ofMinutes(30);
    @Override public Optional<FundProfile> getProfile(FundCode code){return Optional.ofNullable(profiles.get(code.value()));}
    @Override public void putProfile(FundProfile profile){profiles.put(profile.code().value(),profile);}
    @Override public Optional<List<NavPoint>> getHistory(FundCode code,LocalDate startDate,LocalDate endDate){String key=code.value()+"|"+startDate+"|"+endDate;HistoryEntry entry=history.get(key);if(entry==null)return Optional.empty();if(entry.cachedAt().plus(HISTORY_TTL).isBefore(Instant.now())){history.remove(key,entry);return Optional.empty();}return Optional.of(entry.points());}
    @Override public void putHistory(FundCode code,LocalDate startDate,LocalDate endDate,List<NavPoint> points){history.put(code.value()+"|"+startDate+"|"+endDate,new HistoryEntry(List.copyOf(points),Instant.now()));}
    @Override public void evict(FundCode code){profiles.remove(code.value());history.keySet().removeIf(k->k.startsWith(code.value()+"|"));}
    private record HistoryEntry(List<NavPoint> points,Instant cachedAt){}
}
