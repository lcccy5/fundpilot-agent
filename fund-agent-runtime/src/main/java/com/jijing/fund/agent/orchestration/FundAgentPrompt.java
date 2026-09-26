package com.jijing.fund.agent.orchestration;

/**
 * 一份已加载的系统提示词及其版本和内容摘要。
 * 版本或正文为空时构造失败，调用方不能用空提示词启动运行。
 * 远程解析失败时是否回退到本地提示词由解析器决定，本对象只拒绝不完整内容。
 */
public record FundAgentPrompt(String version, String content, String sha256) {

    /**
     * 拒绝空白版本或空白正文。
     * 摘要不在这里重算；调用方传入的摘要与正文不一致时不会被本构造器发现。
     */
    public FundAgentPrompt {
        if (version == null || version.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("Agent prompt version and content are required");
        }
    }
}
