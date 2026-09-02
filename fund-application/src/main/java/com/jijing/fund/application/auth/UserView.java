package com.jijing.fund.application.auth;
import com.jijing.fund.domain.identity.*;
import java.util.Set;
public record UserView(UserId userId,String username,String displayName,Set<UserRole> roles) {}
