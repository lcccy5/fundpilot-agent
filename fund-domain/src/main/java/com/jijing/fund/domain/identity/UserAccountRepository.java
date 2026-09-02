package com.jijing.fund.domain.identity;

import java.time.Instant;
import java.util.Optional;

public interface UserAccountRepository {
    Optional<UserAccount> findByUsername(String normalizedUsername);
    Optional<UserAccount> findById(UserId userId);
    void save(UserAccount account);
    void saveRefreshToken(RefreshTokenRecord token);
    Optional<RefreshTokenRecord> findRefreshTokenByHash(String hash);
    void revokeRefreshFamily(String familyId, Instant revokedAt);
    void revokeRefreshToken(String tokenId,String replacementTokenId,Instant revokedAt);
    void incrementTokenVersion(UserId userId,Instant updatedAt);
    void revokeAllRefreshTokens(UserId userId,Instant revokedAt);
    void updateStatus(UserId userId,UserStatus status,Instant updatedAt);
}
