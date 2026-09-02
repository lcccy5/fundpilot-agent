package com.jijing.fund.domain.identity;

import java.time.Instant;

public interface AccessTokenIssuer { String issue(UserAccount account,String sessionId,Instant expiresAt); }
