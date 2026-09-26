package com.jijing.fund.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jijing.fund.infrastructure.persistence.entity.FundNavEntity;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code fund_nav} 表的映射。区间查询没有命中时返回空列表，最新一条没有命中时返回 null。
 * 同一基金代码和净值日再次写入走重复键更新，只覆盖净值、状态和数据源。
 */
@Mapper
public interface FundNavMapper extends BaseMapper<FundNavEntity> {
    /**
     * 读取闭区间内的净值，按日期升序。没有行时返回空列表。
     * {@code code}、{@code start} 或 {@code end} 为 null 时，比较结果为未知，返回空列表。
     */
    @Select("SELECT * FROM fund_nav WHERE fund_code=#{code} AND nav_date BETWEEN #{start} AND #{end} ORDER BY nav_date")
    List<FundNavEntity> findHistory(@Param("code") String code, @Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * 读取该基金日期最大的一条净值。没有行时返回 null。{@code code} 为 null 时同样返回 null。
     */
    @Select("SELECT * FROM fund_nav WHERE fund_code=#{code} ORDER BY nav_date DESC LIMIT 1")
    FundNavEntity findLatest(@Param("code") String code);

    /**
     * 按 {@code (fund_code, nav_date)} 批量插入或覆盖净值。列表为空时 foreach 不会生成值元组，SQL 无效。
     * 列表为 null 时语句无法绑定集合。重复的代码和日期由数据库覆盖单位净值、累计净值、复权净值、状态和数据源，
     * 不更新 {@code source_updated_at} 与 {@code collected_at}。返回驱动报告的受影响行数。
     */
    @Insert({
        "<script>",
        "INSERT INTO fund_nav(fund_code,nav_date,unit_nav,accumulated_nav,adjusted_nav,nav_status,data_source,source_updated_at,collected_at) VALUES",
        "<foreach collection='items' item='i' separator=','>(#{i.fundCode},#{i.navDate},#{i.unitNav},#{i.accumulatedNav},#{i.adjustedNav},#{i.navStatus},#{i.dataSource},#{i.sourceUpdatedAt},#{i.collectedAt})</foreach>",
        "ON DUPLICATE KEY UPDATE unit_nav=VALUES(unit_nav),accumulated_nav=VALUES(accumulated_nav),adjusted_nav=VALUES(adjusted_nav),nav_status=VALUES(nav_status),data_source=VALUES(data_source)",
        "</script>"
    })
    int upsertBatch(@Param("items") List<FundNavEntity> items);
}
