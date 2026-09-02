package com.jijing.fund.infrastructure.cache;

import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.model.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="fund.cache",name="redis-enabled",havingValue="false")
public class InMemoryFundQueryCache implements FundQueryCache {
    private final Map<String,FundProfile> profiles=new ConcurrentHashMap<>();
    private final Map<String,List<NavPoint>> history=new ConcurrentHashMap<>();
    @Override public Optional<FundProfile> getProfile(FundCode code){return Optional.ofNullable(profiles.get(code.value()));}
    @Override public void putProfile(FundProfile profile){profiles.put(profile.code().value(),profile);}
    @Override public Optional<List<NavPoint>> getHistory(FundCode code,LocalDate startDate,LocalDate endDate){return Optional.ofNullable(history.get(code.value()+"|"+startDate+"|"+endDate));}
    @Override public void putHistory(FundCode code,LocalDate startDate,LocalDate endDate,List<NavPoint> points){history.put(code.value()+"|"+startDate+"|"+endDate,List.copyOf(points));}
    @Override public void evict(FundCode code){profiles.remove(code.value());history.keySet().removeIf(k->k.startsWith(code.value()+"|"));}
}
