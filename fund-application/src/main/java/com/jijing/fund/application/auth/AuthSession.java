package com.jijing.fund.application.auth;
import com.jijing.fund.domain.identity.UserId;
import java.time.Instant;
public record AuthSession(UserId userId,String accessToken,String refreshToken,Instant accessTokenExpiresAt,UserView user) {}
