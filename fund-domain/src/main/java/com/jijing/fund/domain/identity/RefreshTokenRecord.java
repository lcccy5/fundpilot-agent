package com.jijing.fund.domain.identity;

import java.time.Instant;

public record RefreshTokenRecord(String tokenId,UserId userId,String familyId,String tokenHash,Instant expiresAt,
                                 Instant revokedAt,String replacedByTokenId,Instant createdAt) {
    public boolean activeAt(Instant now){return revokedAt==null&&expiresAt.isAfter(now);}
}
