package com.jijing.fund.agent.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 校验一次已签发的参数审批是否仍可放行当前动作。
 * 审批被使用、过期、缺少时间或参数摘要不一致时视为拒绝，调用方必须停在副作用之前。
 * 本类不生成计划、不选择路由，也不接管对等代理的失败。
 */
public final class ApprovalService {

    /**
     * 对照已保存的参数摘要，判断审批此刻是否仍然有效。
     * 已使用、过期时间为空、当前时间为空、当前时间晚于过期时间，或摘要与当前参数不一致时返回 false，
     * 调用方应把该结果当作审批拒绝，不得继续导出、发布或通知。
     * 过期时间与当前时间相等时仍视为未过期。计划、路由或对等代理失败不在这里被改判。
     */
    public boolean isValid(String storedParameterHash, String currentParameters, Instant expiresAt, Instant now,
            Instant usedAt) {
        if (usedAt != null) {
            return false;
        }
        if (expiresAt == null || now == null || now.isAfter(expiresAt)) {
            return false;
        }
        return Objects.equals(storedParameterHash, hash(currentParameters));
    }

    /**
     * 计算审批所绑定的参数摘要。
     * 参数为 null 时按空字符串处理。摘要算法不可用时抛出 {@link IllegalStateException}，
     * 调用方应中止依赖该审批的执行，而不是放行未绑定参数的动作。
     */
    public String hash(String parameters) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((parameters == null ? "" : parameters).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
