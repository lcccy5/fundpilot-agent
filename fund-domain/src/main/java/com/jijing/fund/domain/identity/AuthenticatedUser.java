package com.jijing.fund.domain.identity;

import java.util.Set;

public record AuthenticatedUser(UserId userId, Set<UserRole> roles, String sessionId) {
    public AuthenticatedUser { roles=roles==null?Set.of():Set.copyOf(roles); }
    public boolean hasRole(UserRole role){return roles.contains(role);}
}
