package com.jijing.fund.agent.orchestration;

/**
 * 一次请求最终使用的提示词，以及可选的发布标识和变体。
 * 本地回退时发布标识为空、变体为 stable。强制版本解析失败时不会构造本对象，而是由解析器抛出异常。
 * 计划、路由、审批或对等代理失败不改写已解析的提示词。
 */
public record ResolvedFundAgentPrompt(
        String version,
        String content,
        String sha256,
        String releaseId,
        String variant) {

    /**
     * 把进程内提示词标成本地稳定版本。
     * 远程控制面不可用且请求没有强制版本时使用该结果，避免把解析失败伪装成另一套提示词。
     */
    public static ResolvedFundAgentPrompt local(FundAgentPrompt prompt) {
        return new ResolvedFundAgentPrompt(prompt.version(), prompt.content(), prompt.sha256(), null, "stable");
    }
}
