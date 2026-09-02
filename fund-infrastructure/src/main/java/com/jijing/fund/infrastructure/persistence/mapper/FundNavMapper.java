package com.jijing.fund.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jijing.fund.infrastructure.persistence.entity.FundNavEntity;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FundNavMapper extends BaseMapper<FundNavEntity> {
    @Select("SELECT * FROM fund_nav WHERE fund_code=#{code} AND nav_date BETWEEN #{start} AND #{end} ORDER BY nav_date")
    List<FundNavEntity> findHistory(@Param("code") String code, @Param("start") LocalDate start, @Param("end") LocalDate end);
    @Select("SELECT * FROM fund_nav WHERE fund_code=#{code} ORDER BY nav_date DESC LIMIT 1") FundNavEntity findLatest(@Param("code") String code);
    @Insert({"<script>", "INSERT INTO fund_nav(fund_code,nav_date,unit_nav,accumulated_nav,adjusted_nav,nav_status,data_source,source_updated_at,collected_at) VALUES",
        "<foreach collection='items' item='i' separator=','>(#{i.fundCode},#{i.navDate},#{i.unitNav},#{i.accumulatedNav},#{i.adjustedNav},#{i.navStatus},#{i.dataSource},#{i.sourceUpdatedAt},#{i.collectedAt})</foreach>",
        "ON DUPLICATE KEY UPDATE unit_nav=VALUES(unit_nav),accumulated_nav=VALUES(accumulated_nav),adjusted_nav=VALUES(adjusted_nav),nav_status=VALUES(nav_status),data_source=VALUES(data_source)", "</script>"})
    int upsertBatch(@Param("items") List<FundNavEntity> items);
}
