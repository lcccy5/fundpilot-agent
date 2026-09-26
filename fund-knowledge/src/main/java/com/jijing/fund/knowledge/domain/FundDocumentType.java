package com.jijing.fund.knowledge.domain;

/**
 * 基金资料的种类：招募说明书、招募说明书更新、年报、中期报告、季报、基金公告、基金合同，以及其他。
 * 种类只作为过滤和切片上的元数据，不改变切块、重排或预算的规则。
 */
public enum FundDocumentType {
    PROSPECTUS,
    PROSPECTUS_UPDATE,
    ANNUAL_REPORT,
    SEMI_ANNUAL_REPORT,
    QUARTERLY_REPORT,
    FUND_ANNOUNCEMENT,
    FUND_CONTRACT,
    OTHER
}
