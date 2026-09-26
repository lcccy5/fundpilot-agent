package com.jijing.fund.domain.repository;

import com.jijing.fund.domain.model.*;
import java.util.*;
import java.time.LocalDate;

/** 基金档案的持久化端口，同时维护启用状态和每只基金的数据修订号（数据变化后递增，用于让缓存和派生结果失效）。 */
public interface FundRepository {
    /** 按基金代码查询档案；基金不存在时返回空。 */
    Optional<FundProfile> findByCode(FundCode fundCode);

    /** 按基金代码插入或覆盖档案，重复保存同一档案是幂等的；保存后该基金被视为启用。 */
    void save(FundProfile profile);

    /** 分页列出启用中的基金代码，用于批量同步；offset 超出范围时返回空列表，负数分页参数的处理由实现决定。 */
    List<FundCode> findEnabledFundCodes(int offset, int limit);

    /** 读取某基金当前的数据修订号；基金不存在或从未递增过时返回 0。 */
    long getDataRevision(FundCode fundCode);

    /** 将某基金的数据修订号加一并记录最新净值日期；每次调用都会递增，因此不是幂等操作。基金不存在时静默不生效。 */
    void incrementDataRevision(FundCode fundCode, LocalDate latestNavDate);
}
