package com.jijing.fund.domain.risk;

import com.jijing.fund.domain.identity.UserId;
import java.time.Instant;

public record RiskProfile(String profileId,UserId ownerUserId,String questionnaireVersion,String answersHash,
                          int score,RiskLevel level,Instant confirmedAt,Instant createdAt) {
    public RiskProfile {
        if(profileId==null||ownerUserId==null||questionnaireVersion==null||answersHash==null||level==null)throw new IllegalArgumentException("risk profile is invalid");
    }
}
