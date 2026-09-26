package com.jijing.fund.testsupport.rag;

import java.util.Collection;
import java.util.Set;

/**
 * 计算回答里证据引用的精确率和覆盖率。
 * 没有实际引用时精确率记为 1，没有必填证据时覆盖率记为 1，避免把“无引用要求”算成失败。
 */
public final class CitationMetricCalculator {

    /**
     * 用允许集合过滤实际引用，再用必填集合计算覆盖。
     * required、allowed 或 actual 为 null 时直接空指针，不会返回指标；实际引用里的重复项按多次计数，精确率可能低于按集合去重的结果。
     */
    public CitationMetrics calculate(Set<String> required, Set<String> allowed, Collection<String> actual) {
        long valid = actual.stream().filter(allowed::contains).count();
        double precision = actual.isEmpty() ? 1 : (double) valid / actual.size();
        long covered = required.stream().filter(actual::contains).count();
        double coverage = required.isEmpty() ? 1 : (double) covered / required.size();
        return new CitationMetrics(precision, coverage);
    }

    /**
     * 一次引用核对的两项分数，取值在 0 到 1。
     * 构造时不校验范围，调用方传入 NaN 也会被原样保存。
     */
    public record CitationMetrics(double precision, double coverage) {}
}
