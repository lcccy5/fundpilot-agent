package com.jijing.fund.agent.capability;

import java.util.List;

/**
 * 能力执行成功后的产出地址和证据标识。
 * 产出地址为空时拒绝构造，避免把没有产物的任务标成成功。
 */
public record CapabilityExecutionResult(String outputUri, List<String> evidenceIds) {

    /**
     * 要求产出地址非空白，并把空证据列表收成不可变空列表。
     * 产出地址为空或只有空白时抛出非法参数，不生成结果。
     */
    public CapabilityExecutionResult {
        if (outputUri == null || outputUri.isBlank()) {
            throw new IllegalArgumentException("outputUri is required");
        }
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
