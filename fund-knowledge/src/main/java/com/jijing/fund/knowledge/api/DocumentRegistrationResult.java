package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.IngestionStatus;

/**
 * 注册受理结果。{@code duplicate} 为 true 表示仓库认定同一来源下的同一内容已经登记过，
 * 此时文档、版本和任务标识指向已有记录，状态也是已有版本的状态，不一定是刚注册。
 * 本模块的注册服务不自行计算这个标志。
 */
public record DocumentRegistrationResult(
        String documentId,
        String versionId,
        String jobId,
        IngestionStatus status,
        boolean duplicate) {}
