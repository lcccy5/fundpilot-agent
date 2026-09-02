package com.jijing.fund.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jijing.fund.infrastructure.persistence.entity.FundSyncRecordEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FundSyncRecordMapper extends BaseMapper<FundSyncRecordEntity> {}

