package com.jijing.fund.application;

import com.jijing.fund.application.dto.FundNavHistoryResult;
import com.jijing.fund.application.dto.FundProfileResult;
import java.time.LocalDate;

/**
 * 面向调用方的基金档案与净值历史查询。
 * 本地没有数据时可以回源并落库；代码或区间无法解释时拒绝执行，而不是返回空结果冒充成功。
 */
public interface FundQueryUseCase {

    /**
     * 读取单只基金的公开档案，并给出采集时间是否仍然新鲜。
     * 代码不是六位数字时抛出无效查询；本地与外部数据源都没有该基金时抛出基金不存在。
     *
     * @param fundCode 调用方传入的基金代码文本
     * @return 档案视图，新鲜度由采集时刻与当前时钟的间隔决定
     */
    FundProfileResult getProfile(String fundCode);

    /**
     * 读取区间内的单位净值序列，并附上相对前一个样本的涨跌幅。
     * 起止日期缺失或起点晚于终点时抛出无效查询；序列装载后仍找不到基金档案时抛出基金不存在。
     *
     * @param fundCode 调用方传入的基金代码文本
     * @param startDate 区间起点，含当日
     * @param endDate 区间终点，含当日
     * @return 净值点列表；没有样本时来源标记为数据库，涨跌幅首项为空
     */
    FundNavHistoryResult getNavHistory(String fundCode, LocalDate startDate, LocalDate endDate);
}
