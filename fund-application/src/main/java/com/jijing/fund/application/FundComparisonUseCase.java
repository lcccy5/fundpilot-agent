package com.jijing.fund.application;

import com.jijing.fund.application.dto.FundComparisonResult;
import java.time.LocalDate;
import java.util.List;

/**
 * 在同一净值口径下比较多只基金的收益与风险指标。
 * 基金数量、重叠区间或净值口径不满足可比条件时拒绝给出排名。
 */
public interface FundComparisonUseCase {

    /**
     * 把多只基金对齐到共同有净值的区间后计算指标并排名。
     * 代码列表为空、去重后不足两只或超过十只时抛出无效查询；没有重叠区间时抛出无重叠区间；各基金净值口径不一致时抛出不支持的净值口径。
     *
     * @param fundCodes 待比较的基金代码，重复项会先去掉
     * @param startDate 调用方请求的区间起点
     * @param endDate 调用方请求的区间终点
     * @param navBasis 请求的净值口径名称，具体是否可算由指标查询决定
     * @return 对齐后的区间、实际口径、各基金指标以及四项排名
     */
    FundComparisonResult compare(List<String> fundCodes, LocalDate startDate, LocalDate endDate, String navBasis);
}
