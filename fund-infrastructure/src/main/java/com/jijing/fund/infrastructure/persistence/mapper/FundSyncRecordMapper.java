package com.jijing.fund.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jijing.fund.infrastructure.persistence.entity.FundSyncRecordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code fund_sync_record} 的 MyBatis-Plus 映射，本接口没有额外语句。
 * {@code selectById} 在主键不存在时返回 null；传入 null 主键时条件不成立，同样返回 null，不会抛出缺行异常。
 * {@code insert} 遇到已占用的主键时由底层抛出重复键异常，本接口不把冲突改写成更新。
 * {@code updateById} 对不存在的主键报告受影响行数 0，且默认不把 null 属性写进 SET 子句。
 */
@Mapper
public interface FundSyncRecordMapper extends BaseMapper<FundSyncRecordEntity> {
}
