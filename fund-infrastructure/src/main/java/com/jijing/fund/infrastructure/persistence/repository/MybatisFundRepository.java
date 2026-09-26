package com.jijing.fund.infrastructure.persistence.repository;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.FundProfile;
import com.jijing.fund.domain.repository.FundRepository;
import com.jijing.fund.infrastructure.persistence.entity.FundEntity;
import com.jijing.fund.infrastructure.persistence.mapper.FundMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 用 {@code fund} 表实现基金主数据仓储。代理主键不进入领域对象。
 * 按代码查不到行时返回空可选值；修订号查不到时返回 0。重复的基金代码交给 upsert 覆盖，本类不先查询也不捕获重复键异常。
 */
@Repository
public class MybatisFundRepository implements FundRepository {
    private final FundMapper mapper;

    /**
     * 保存基金表映射器。这里不访问数据库。映射器为 null 时，后续每次读写都会在调用处抛出空指针。
     */
    public MybatisFundRepository(FundMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 按基金代码读取主数据。映射器返回 null（没有这一行，或代码参数为 null）时返回空可选值。
     * {@code code} 本身为 null 时在取值前抛出空指针，不会去查库。
     * 行能查到但基金代码或名称等必填项为 null 时，领域构造失败，异常直接抛出。
     */
    @Override
    public Optional<FundProfile> findByCode(FundCode code) {
        return Optional.ofNullable(mapper.findByCode(code.value())).map(this::toDomain);
    }

    /**
     * 按基金代码插入或覆盖主数据。实体的代理主键保持 null，由数据库分配；代码冲突时由 upsert 更新主数据列。
     * 不检查受影响行数。映射器若仍抛出重复键异常，本方法不捕获。{@code profile} 为 null 时在组装实体前抛出空指针。
     */
    @Override
    public void save(FundProfile profile) {
        mapper.upsert(toEntity(profile));
    }

    /**
     * 分页返回已启用基金的代码。没有命中时返回空列表。
     * 某一行的基金代码为 null 或不是六位数字时，领域构造失败，整页结果中断。
     */
    @Override
    public List<FundCode> findEnabledFundCodes(int offset, int limit) {
        return mapper.findEnabled(offset, limit).stream().map(e -> new FundCode(e.getFundCode())).toList();
    }

    /**
     * 读取数据修订号。映射器返回 null（基金不存在，或列值为 SQL NULL）时都返回 0，调用方看不出缺的是整行还是列。
     * {@code code} 为 null 时在取值前抛出空指针。
     */
    @Override
    public long getDataRevision(FundCode code) {
        Long revision = mapper.getDataRevision(code.value());
        return revision == null ? 0L : revision;
    }

    /**
     * 把该基金的修订号加一，并写入最新净值日。基金不存在时更新行数为 0，本方法仍然正常返回。
     * {@code latestNavDate} 为 null 时，已存在的行会把最新净值日写成 SQL NULL。{@code code} 为 null 时在取值前抛出空指针。
     */
    @Override
    public void incrementDataRevision(FundCode code, LocalDate latestNavDate) {
        mapper.incrementDataRevision(code.value(), latestNavDate);
    }

    /**
     * 把已存在的基金行转成领域档案。不读取代理主键、启用标记、修订号和最新净值日。
     * 基金代码、名称、数据源或采集时间为 null 时，领域构造抛出异常；类型、管理人、经理、成立日和源更新时间允许为 null。
     */
    private FundProfile toDomain(FundEntity e) {
        return new FundProfile(
                new FundCode(e.getFundCode()),
                e.getFundName(),
                e.getFundType(),
                e.getManagementCompany(),
                e.getFundManager(),
                e.getEstablishedDate(),
                e.getDataSource(),
                e.getSourceUpdatedAt(),
                e.getCollectedAt());
    }

    /**
     * 组装待写入的基金行。代理主键、修订号和最新净值日保持 null，不参与这条 upsert。
     * 启用标记在内存中设为 true，但写入语句不读取该属性，新行的启用值来自 SQL 字面量 1。
     */
    private FundEntity toEntity(FundProfile p) {
        FundEntity e = new FundEntity();
        e.setFundCode(p.code().value());
        e.setFundName(p.name());
        e.setFundType(p.fundType());
        e.setManagementCompany(p.managementCompany());
        e.setFundManager(p.fundManager());
        e.setEstablishedDate(p.establishedDate());
        e.setEnabled(true);
        e.setDataSource(p.dataSource());
        e.setSourceUpdatedAt(p.sourceUpdatedAt());
        e.setCollectedAt(p.collectedAt());
        return e;
    }
}
