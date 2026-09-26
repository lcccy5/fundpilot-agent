package com.jijing.fund.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jijing.fund.infrastructure.persistence.entity.FundEntity;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code fund} 表的映射。查询未命中时，单个对象返回 null，列表返回空列表。
 * 代码唯一键冲突由 upsert 覆盖主数据列；代理主键冲突仍会从底层抛出，本接口不捕获。
 */
@Mapper
public interface FundMapper extends BaseMapper<FundEntity> {
    /**
     * 按基金代码取一行。没有该代码时返回 null，不会返回字段为空的实体。
     * {@code code} 为 null 时条件与任何行都不相等，结果同样是 null。
     */
    @Select("SELECT * FROM fund WHERE fund_code = #{code} LIMIT 1")
    FundEntity findByCode(@Param("code") String code);

    /**
     * 按代码顺序分页读取 {@code enabled = 1} 的基金。没有命中时返回空列表。
     * 启用列为 NULL 的行不会出现。{@code offset}、{@code limit} 原样进入 SQL，调用方传入负数时由数据库拒绝。
     */
    @Select("SELECT * FROM fund WHERE enabled = 1 ORDER BY fund_code LIMIT #{limit} OFFSET #{offset}")
    List<FundEntity> findEnabled(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 按 {@code fund_code} 插入或覆盖主数据。新行的 {@code enabled} 固定写成 1，修订号和最新净值日走数据库默认值（0 和 NULL）。
     * 唯一键已存在时只更新名称、类型、管理人、经理、成立日、数据源和两个采集时间，不改启用标记、修订号和最新净值日。
     * 语句中的 null 绑定为 SQL NULL；非空列因此失败。返回值是驱动报告的受影响行数，调用方目前不使用。
     */
    @Insert("""
        INSERT INTO fund(fund_code,fund_name,fund_type,management_company,fund_manager,established_date,enabled,data_source,source_updated_at,collected_at)
        VALUES(#{fundCode},#{fundName},#{fundType},#{managementCompany},#{fundManager},#{establishedDate},1,#{dataSource},#{sourceUpdatedAt},#{collectedAt})
        ON DUPLICATE KEY UPDATE fund_name=VALUES(fund_name),fund_type=VALUES(fund_type),management_company=VALUES(management_company),
        fund_manager=VALUES(fund_manager),established_date=VALUES(established_date),data_source=VALUES(data_source),
        source_updated_at=VALUES(source_updated_at),collected_at=VALUES(collected_at)
        """)
    int upsert(FundEntity entity);

    /**
     * 读取该基金的数据修订号。没有这一行，或列值本身为 SQL NULL 时，都返回 null。
     * {@code code} 为 null 时匹配不到行，返回 null。
     */
    @Select("SELECT data_revision FROM fund WHERE fund_code=#{code}")
    Long getDataRevision(@Param("code") String code);

    /**
     * 把修订号加一，并写入最新净值日。基金不存在时受影响行数为 0，修订号不变。
     * {@code latestNavDate} 为 null 时，已存在的行会把 {@code latest_nav_date} 更新成 SQL NULL，修订号仍然加一。
     */
    @Update("UPDATE fund SET data_revision=data_revision+1, latest_nav_date=#{latestNavDate} WHERE fund_code=#{code}")
    int incrementDataRevision(@Param("code") String code, @Param("latestNavDate") LocalDate latestNavDate);
}
