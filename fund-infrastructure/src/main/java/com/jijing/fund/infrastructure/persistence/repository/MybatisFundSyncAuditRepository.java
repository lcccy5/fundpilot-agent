package com.jijing.fund.infrastructure.persistence.repository;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.repository.FundSyncAuditRepository;
import com.jijing.fund.infrastructure.persistence.entity.FundSyncRecordEntity;
import com.jijing.fund.infrastructure.persistence.mapper.FundSyncRecordMapper;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把一次基金同步写成 {@code fund_sync_record} 的独立事务。开始、成功、失败各自使用新事务，调用方回滚不会撤销审计。
 * 表没有业务唯一键；只有显式主键冲突才会产生重复键异常，本类不捕获。
 * 成功和失败在主键不存在时直接返回，不插入补记，也不抛出缺行异常。
 */
@Repository
public class MybatisFundSyncAuditRepository implements FundSyncAuditRepository {
    private final FundSyncRecordMapper mapper;

    /**
     * 保存同步审计映射器。这里不访问数据库。映射器为 null 时，后续写入在调用处抛出空指针。
     */
    public MybatisFundSyncAuditRepository(FundSyncRecordMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 插入一条状态为 {@code RUNNING}、类型为 {@code FULL}、请求和落库条数都为 0 的审计。
     * 登记时间和开始时间各自取一次当前时刻，两者可以相差一个极短间隔。错误码、错误说明和结束时间保持 null，插入时这些列留空。
     * {@code start} 或 {@code end} 为 null 时，对应日期列保存为 NULL。{@code code} 为 null 时在取值前抛出空指针。
     * 插入后若主键仍未被回填，返回值拆箱会抛出空指针。主键冲突异常原样抛出。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(FundCode code, String source, LocalDate start, LocalDate end) {
        FundSyncRecordEntity e = new FundSyncRecordEntity();
        e.setFundCode(code.value());
        e.setSourceName(source);
        e.setSyncType("FULL");
        e.setDateRangeStart(start);
        e.setDateRangeEnd(end);
        e.setRequestedCount(0);
        e.setSavedCount(0);
        e.setSyncStatus("RUNNING");
        e.setSyncedAt(Instant.now());
        e.setStartedAt(Instant.now());
        mapper.insert(e);
        return e.getId();
    }

    /**
     * 把已存在的审计改成 {@code SUCCESS}，并写下请求条数、落库条数和结束时间。
     * 主键没有对应行时直接返回，不更新。错误码和错误说明保持原值，成功路径不会清空它们。
     * {@code finishedAt} 为 null 时，按主键更新会跳过该列，库里的结束时间不变。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(long id, int requested, int saved, Instant finishedAt) {
        FundSyncRecordEntity e = mapper.selectById(id);
        if (e == null) {
            return;
        }
        e.setRequestedCount(requested);
        e.setSavedCount(saved);
        e.setSyncStatus("SUCCESS");
        e.setFinishedAt(finishedAt);
        mapper.updateById(e);
    }

    /**
     * 把已存在的审计改成 {@code FAILED}，并写下错误码、错误说明和结束时间。
     * 主键没有对应行时直接返回，不更新。请求条数和落库条数保持原值。
     * {@code code}、{@code message} 或 {@code finishedAt} 为 null 时，按主键更新会跳过对应列，已经写入的错误信息不会被清空。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(long id, String code, String message, Instant finishedAt) {
        FundSyncRecordEntity e = mapper.selectById(id);
        if (e == null) {
            return;
        }
        e.setSyncStatus("FAILED");
        e.setErrorCode(code);
        e.setErrorMessage(message);
        e.setFinishedAt(finishedAt);
        mapper.updateById(e);
    }
}
