package com.jijing.fund.agent.orchestration;

/**
 * 回答句子在引用校验中的分类。
 * 数值、文档和解释类声明缺少兼容证据时，引用策略会补引用或拒绝回答。
 * 局限性和通识教育不强制证据。分类错误不会改写计划、路由或审批结果。
 */
public enum ClaimType {
    /** 含基金代码或百分数，必须引用非文档类证据。无法补上时回答被拒绝。 */
    NUMERIC_FACT,
    /** 引用公告、报告或事件，必须引用文档类证据。撰写者不能用研究产物冒充此类声明。 */
    DOCUMENT_FACT,
    /** 推断性表述，任意已存在证据即可。证据列表为空时无法修复。 */
    INTERPRETATION,
    /** 风险提示或免责声明，不因缺少证据而失败。 */
    LIMITATION,
    /** 不依赖本次工具证据的一般说明。凭空编造的证据编号仍会被拒绝。 */
    GENERAL_EDUCATION
}
