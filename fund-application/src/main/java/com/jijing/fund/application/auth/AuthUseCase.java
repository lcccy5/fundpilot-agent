package com.jijing.fund.application.auth;

import com.jijing.fund.domain.identity.*;

public interface AuthUseCase {
    AuthSession register(RegisterCommand command);
    AuthSession login(LoginCommand command);
    AuthSession refresh(String rawRefreshToken);
    void logout(AuthenticatedUser actor);
    UserView me(AuthenticatedUser actor);
    void deactivate(AuthenticatedUser actor);
}
