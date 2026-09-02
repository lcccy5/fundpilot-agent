package com.jijing.fund.domain.identity;

import java.time.Instant;
import java.util.Set;

public record UserAccount(UserId userId,String normalizedUsername,String displayName,String passwordHash,
                          UserStatus status,int tokenVersion,Set<UserRole> roles,Instant createdAt,Instant updatedAt) {
    public UserAccount { if(normalizedUsername==null||normalizedUsername.isBlank())throw new IllegalArgumentException("username is required"); roles=roles==null?Set.of():Set.copyOf(roles); }
}
