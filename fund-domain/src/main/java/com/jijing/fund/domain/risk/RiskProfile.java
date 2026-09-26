package com.jijing.fund.domain.risk;

import com.jijing.fund.domain.identity.UserId;
import java.time.Instant;

/**
 * 用户一次已确认的风险测评结果：问卷版本、答案指纹、得分、风险等级和确认时间。
 * 只保存答案哈希而非原始答案；同一用户可有多条记录，以最近确认的一条为准。
 */
public record RiskProfile(String profileId, UserId ownerUserId, String questionnaireVersion, String answersHash,
                          int score, RiskLevel level, Instant confirmedAt, Instant createdAt) {
    /**
     * 校验测评结果必填项：profileId、ownerUserId、questionnaireVersion、answersHash、level 任一为 null 时抛出
     * IllegalArgumentException（"risk profile is invalid"）。只检查 null，空白字符串、负分和 null 时间都会被接受。
     */
    public RiskProfile {
        if (profileId == null
                || ownerUserId == null
                || questionnaireVersion == null
                || answersHash == null
                || level == null) {
            throw new IllegalArgumentException("risk profile is invalid");
        }
    }
}
