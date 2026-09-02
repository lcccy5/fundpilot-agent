package com.jijing.fund.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import com.jijing.fund.domain.identity.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AuthLogoutRevokesRefreshTokensTest {
    @Test void logoutRevokesAllRefreshTokensAndBumpsVersion(){
        var users=new FakeUsers();
        var now=Instant.parse("2026-08-27T08:00:00Z");
        var id=new UserId("00000000-0000-0000-0000-0000000000aa");
        users.account=new UserAccount(id,"alice","Alice","hash",UserStatus.ACTIVE,0,Set.of(UserRole.USER),now,now);
        var service=new AuthApplicationService(users,new PasswordHasher(){public String hash(String r){return "h";}public boolean matches(String r,String s){return true;}},(a,s,e)->"token",Clock.fixed(now,ZoneOffset.UTC),Duration.ofMinutes(15),Duration.ofDays(30));
        service.logout(new AuthenticatedUser(id,Set.of(UserRole.USER),"sid"));
        assertThat(users.revokedAll).isTrue();
        assertThat(users.bumped).isTrue();
    }
    private static final class FakeUsers implements UserAccountRepository {
        UserAccount account;boolean revokedAll;boolean bumped;
        @Override public Optional<UserAccount> findByUsername(String n){return Optional.ofNullable(account);}
        @Override public Optional<UserAccount> findById(UserId id){return Optional.ofNullable(account);}
        @Override public void save(UserAccount a){account=a;}
        @Override public void saveRefreshToken(RefreshTokenRecord token){}
        @Override public Optional<RefreshTokenRecord> findRefreshTokenByHash(String hash){return Optional.empty();}
        @Override public void revokeRefreshFamily(String familyId,Instant revokedAt){}
        @Override public void revokeRefreshToken(String tokenId,String replacementTokenId,Instant revokedAt){}
        @Override public void incrementTokenVersion(UserId userId,Instant updatedAt){bumped=true;}
        @Override public void revokeAllRefreshTokens(UserId userId,Instant revokedAt){revokedAll=true;}
        @Override public void updateStatus(UserId userId,UserStatus status,Instant updatedAt){}
    }
}
