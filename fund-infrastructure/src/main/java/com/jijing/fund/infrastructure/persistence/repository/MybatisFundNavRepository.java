package com.jijing.fund.infrastructure.persistence.repository;

import com.jijing.fund.domain.model.*;
import com.jijing.fund.domain.repository.FundNavRepository;
import com.jijing.fund.infrastructure.persistence.entity.FundNavEntity;
import com.jijing.fund.infrastructure.persistence.mapper.FundNavMapper;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisFundNavRepository implements FundNavRepository {
    private final FundNavMapper mapper;
    public MybatisFundNavRepository(FundNavMapper mapper) { this.mapper = mapper; }
    @Override public List<NavPoint> findHistory(FundCode code, LocalDate start, LocalDate end) { return mapper.findHistory(code.value(), start, end).stream().map(this::toDomain).toList(); }
    @Override public Optional<NavPoint> findLatest(FundCode code) { return Optional.ofNullable(mapper.findLatest(code.value())).map(this::toDomain); }
    @Override public int upsertBatch(List<NavPoint> points) { return points.isEmpty() ? 0 : mapper.upsertBatch(points.stream().map(this::toEntity).toList()); }
    private NavPoint toDomain(FundNavEntity e) { return new NavPoint(new FundCode(e.getFundCode()), e.getNavDate(), e.getUnitNav(),
            e.getAccumulatedNav(), e.getAdjustedNav(), NavStatus.valueOf(e.getNavStatus()), e.getDataSource(), e.getSourceUpdatedAt(), e.getCollectedAt()); }
    private FundNavEntity toEntity(NavPoint p) { var e = new FundNavEntity(); e.setFundCode(p.fundCode().value()); e.setNavDate(p.navDate());
        e.setUnitNav(p.unitNav()); e.setAccumulatedNav(p.accumulatedNav()); e.setAdjustedNav(p.adjustedNav()); e.setNavStatus(p.navStatus().name()); e.setDataSource(p.dataSource());
        e.setSourceUpdatedAt(p.sourceUpdatedAt()); e.setCollectedAt(p.collectedAt()); return e; }
}
