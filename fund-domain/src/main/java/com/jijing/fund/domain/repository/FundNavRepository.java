package com.jijing.fund.domain.repository;

import com.jijing.fund.domain.model.*;
import java.time.LocalDate;
import java.util.*;

/** 基金净值的持久化端口，负责按区间查询、取最新一条和批量写入。接口不校验参数，null 的处理由实现决定。 */
public interface FundNavRepository {
    /** 查询某基金在 [startDate, endDate] 区间内的净值；区间内没有数据或起始日期晚于结束日期时返回空列表。 */
    List<NavPoint> findHistory(FundCode code, LocalDate startDate, LocalDate endDate);

    /** 查询某基金净值日期最新的一条记录；没有任何净值时返回空。 */
    Optional<NavPoint> findLatest(FundCode code);

    /**
     * 按基金代码和净值日期插入或覆盖一批净值，重复写入同一批数据是幂等的；返回存储层报告的受影响行数。
     * 传入空列表时不访问存储并返回 0。
     */
    int upsertBatch(List<NavPoint> navPoints);
}
