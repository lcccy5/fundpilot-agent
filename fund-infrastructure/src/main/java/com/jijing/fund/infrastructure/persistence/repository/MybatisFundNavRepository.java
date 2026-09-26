package com.jijing.fund.infrastructure.persistence.repository;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.NavPoint;
import com.jijing.fund.domain.model.NavStatus;
import com.jijing.fund.domain.repository.FundNavRepository;
import com.jijing.fund.infrastructure.persistence.entity.FundNavEntity;
import com.jijing.fund.infrastructure.persistence.mapper.FundNavMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 用 {@code fund_nav} 表实现净值仓储。缺行在最新净值上表现为空可选值，在历史区间上表现为空列表。
 * 批量写入不在内存里合并同一代码和日期，重复键由数据库覆盖净值列。
 */
@Repository
public class MybatisFundNavRepository implements FundNavRepository {
    private final FundNavMapper mapper;

    /**
     * 保存净值映射器。这里不访问数据库。映射器为 null 时，后续读写在调用处抛出空指针。
     */
    public MybatisFundNavRepository(FundNavMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 读取闭区间净值。映射器返回空列表时得到空列表。区间端点或基金代码为 null 时，底层比较不命中，结果同样是空列表。
     * {@code code} 本身为 null 时在取值前抛出空指针。映射器若返回 null 列表，流式转换会抛出空指针。
     * 行内状态或基金代码无法转换成领域对象时，异常从该行抛出，已转换的前缀不会返回。
     */
    @Override
    public List<NavPoint> findHistory(FundCode code, LocalDate start, LocalDate end) {
        return mapper.findHistory(code.value(), start, end).stream().map(this::toDomain).toList();
    }

    /**
     * 读取最新一条净值。映射器返回 null 时返回空可选值。{@code code} 为 null 时在取值前抛出空指针。
     * 查到的行若缺少状态或必填净值，领域构造失败。
     */
    @Override
    public Optional<NavPoint> findLatest(FundCode code) {
        return Optional.ofNullable(mapper.findLatest(code.value())).map(this::toDomain);
    }

    /**
     * 批量插入或覆盖净值。空列表直接返回 0，不调用映射器。列表为 null 时在判空处抛出空指针。
     * 同一代码和日期出现多次时全部交给数据库，本方法不去重。代理主键保持 null。
     * 映射器抛出的重复键异常不捕获。返回映射器给出的受影响行数。
     */
    @Override
    public int upsertBatch(List<NavPoint> points) {
        if (points.isEmpty()) {
            return 0;
        }
        return mapper.upsertBatch(points.stream().map(this::toEntity).toList());
    }

    /**
     * 把净值行转成领域点。不读取代理主键。
     * 状态列为 null，或不是 {@code CONFIRMED}、{@code ESTIMATED}、{@code CORRECTED} 之一时，枚举转换失败。
     * 累计净值和复权净值允许为 null；单位净值、代码、日期、数据源、采集时间缺失时领域构造失败。
     */
    private NavPoint toDomain(FundNavEntity e) {
        return new NavPoint(
                new FundCode(e.getFundCode()),
                e.getNavDate(),
                e.getUnitNav(),
                e.getAccumulatedNav(),
                e.getAdjustedNav(),
                NavStatus.valueOf(e.getNavStatus()),
                e.getDataSource(),
                e.getSourceUpdatedAt(),
                e.getCollectedAt());
    }

    /**
     * 组装待写入的净值行。代理主键保持 null，冲突身份是基金代码加净值日。
     * 累计净值、复权净值和源更新时间允许为 null，并会随插入或重复键更新写入 SQL NULL。
     */
    private FundNavEntity toEntity(NavPoint p) {
        FundNavEntity e = new FundNavEntity();
        e.setFundCode(p.fundCode().value());
        e.setNavDate(p.navDate());
        e.setUnitNav(p.unitNav());
        e.setAccumulatedNav(p.accumulatedNav());
        e.setAdjustedNav(p.adjustedNav());
        e.setNavStatus(p.navStatus().name());
        e.setDataSource(p.dataSource());
        e.setSourceUpdatedAt(p.sourceUpdatedAt());
        e.setCollectedAt(p.collectedAt());
        return e;
    }
}
