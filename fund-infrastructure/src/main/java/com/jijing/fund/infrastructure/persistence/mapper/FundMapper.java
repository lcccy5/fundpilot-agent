package com.jijing.fund.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jijing.fund.infrastructure.persistence.entity.FundEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FundMapper extends BaseMapper<FundEntity> {
    @Select("SELECT * FROM fund WHERE fund_code = #{code} LIMIT 1") FundEntity findByCode(@Param("code") String code);
    @Select("SELECT * FROM fund WHERE enabled = 1 ORDER BY fund_code LIMIT #{limit} OFFSET #{offset}") List<FundEntity> findEnabled(@Param("offset") int offset, @Param("limit") int limit);
    @Insert("""
        INSERT INTO fund(fund_code,fund_name,fund_type,management_company,fund_manager,established_date,enabled,data_source,source_updated_at,collected_at)
        VALUES(#{fundCode},#{fundName},#{fundType},#{managementCompany},#{fundManager},#{establishedDate},1,#{dataSource},#{sourceUpdatedAt},#{collectedAt})
        ON DUPLICATE KEY UPDATE fund_name=VALUES(fund_name),fund_type=VALUES(fund_type),management_company=VALUES(management_company),
        fund_manager=VALUES(fund_manager),established_date=VALUES(established_date),data_source=VALUES(data_source),
        source_updated_at=VALUES(source_updated_at),collected_at=VALUES(collected_at)
        """) int upsert(FundEntity entity);
    @Select("SELECT data_revision FROM fund WHERE fund_code=#{code}") Long getDataRevision(@Param("code") String code);
    @Update("UPDATE fund SET data_revision=data_revision+1, latest_nav_date=#{latestNavDate} WHERE fund_code=#{code}")
    int incrementDataRevision(@Param("code") String code, @Param("latestNavDate") java.time.LocalDate latestNavDate);
}
