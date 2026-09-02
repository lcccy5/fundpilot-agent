package com.jijing.fund.infrastructure.persistence.repository;

import com.jijing.fund.domain.model.*;
import com.jijing.fund.domain.repository.FundRepository;
import com.jijing.fund.infrastructure.persistence.entity.FundEntity;
import com.jijing.fund.infrastructure.persistence.mapper.FundMapper;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisFundRepository implements FundRepository {
    private final FundMapper mapper;
    public MybatisFundRepository(FundMapper mapper) { this.mapper = mapper; }
    @Override public Optional<FundProfile> findByCode(FundCode code) { return Optional.ofNullable(mapper.findByCode(code.value())).map(this::toDomain); }
    @Override public void save(FundProfile profile) { mapper.upsert(toEntity(profile)); }
    @Override public List<FundCode> findEnabledFundCodes(int offset, int limit) { return mapper.findEnabled(offset, limit).stream().map(e -> new FundCode(e.getFundCode())).toList(); }
    @Override public long getDataRevision(FundCode code) { Long revision=mapper.getDataRevision(code.value()); return revision==null?0L:revision; }
    @Override public void incrementDataRevision(FundCode code, java.time.LocalDate latestNavDate) { mapper.incrementDataRevision(code.value(),latestNavDate); }
    private FundProfile toDomain(FundEntity e) { return new FundProfile(new FundCode(e.getFundCode()), e.getFundName(), e.getFundType(),
            e.getManagementCompany(), e.getFundManager(), e.getEstablishedDate(), e.getDataSource(), e.getSourceUpdatedAt(), e.getCollectedAt()); }
    private FundEntity toEntity(FundProfile p) {
        var e = new FundEntity(); e.setFundCode(p.code().value()); e.setFundName(p.name()); e.setFundType(p.fundType());
        e.setManagementCompany(p.managementCompany()); e.setFundManager(p.fundManager()); e.setEstablishedDate(p.establishedDate());
        e.setEnabled(true); e.setDataSource(p.dataSource()); e.setSourceUpdatedAt(p.sourceUpdatedAt()); e.setCollectedAt(p.collectedAt()); return e;
    }
}
