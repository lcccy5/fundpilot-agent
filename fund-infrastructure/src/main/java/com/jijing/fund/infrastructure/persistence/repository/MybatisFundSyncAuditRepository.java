package com.jijing.fund.infrastructure.persistence.repository;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.repository.FundSyncAuditRepository;
import com.jijing.fund.infrastructure.persistence.entity.FundSyncRecordEntity;
import com.jijing.fund.infrastructure.persistence.mapper.FundSyncRecordMapper;
import java.time.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class MybatisFundSyncAuditRepository implements FundSyncAuditRepository {
    private final FundSyncRecordMapper mapper;
    public MybatisFundSyncAuditRepository(FundSyncRecordMapper mapper) { this.mapper = mapper; }
    @Override @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(FundCode code, String source, LocalDate start, LocalDate end) {
        var e = new FundSyncRecordEntity(); e.setFundCode(code.value()); e.setSourceName(source); e.setSyncType("FULL");
        e.setDateRangeStart(start); e.setDateRangeEnd(end); e.setRequestedCount(0); e.setSavedCount(0); e.setSyncStatus("RUNNING");
        e.setSyncedAt(Instant.now()); e.setStartedAt(Instant.now());
        mapper.insert(e); return e.getId();
    }
    @Override @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(long id, int requested, int saved, Instant finishedAt) { var e = mapper.selectById(id); if (e == null) return;
        e.setRequestedCount(requested); e.setSavedCount(saved); e.setSyncStatus("SUCCESS"); e.setFinishedAt(finishedAt); mapper.updateById(e); }
    @Override @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(long id, String code, String message, Instant finishedAt) { var e = mapper.selectById(id); if (e == null) return;
        e.setSyncStatus("FAILED"); e.setErrorCode(code); e.setErrorMessage(message); e.setFinishedAt(finishedAt); mapper.updateById(e); }
}
