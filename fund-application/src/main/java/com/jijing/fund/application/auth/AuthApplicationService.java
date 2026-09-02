package com.jijing.fund.application.auth;

import com.jijing.fund.domain.identity.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

public class AuthApplicationService implements AuthUseCase {
    private final UserAccountRepository users;private final PasswordHasher passwords;private final AccessTokenIssuer tokens;private final Clock clock;private final Duration accessTtl,refreshTtl;
    public AuthApplicationService(UserAccountRepository users,PasswordHasher passwords,AccessTokenIssuer tokens,Clock clock,Duration accessTtl,Duration refreshTtl){this.users=users;this.passwords=passwords;this.tokens=tokens;this.clock=clock;this.accessTtl=accessTtl;this.refreshTtl=refreshTtl;}
    @Override @Transactional public AuthSession register(RegisterCommand command){
        String username=normalize(command.username());validatePassword(command.password());if(users.findByUsername(username).isPresent())throw new AuthException("username is unavailable");
        Instant now=clock.instant();var account=new UserAccount(UserId.random(),username,displayName(command.displayName(),username),passwords.hash(command.password()),UserStatus.ACTIVE,0,Set.of(UserRole.USER),now,now);users.save(account);return session(account);
    }
    @Override @Transactional public AuthSession login(LoginCommand command){
        var account=users.findByUsername(normalize(command.username())).filter(a->a.status()==UserStatus.ACTIVE).orElseThrow(()->new AuthException("invalid username or password"));
        if(!passwords.matches(command.password(),account.passwordHash()))throw new AuthException("invalid username or password");return session(account);
    }
    @Override @Transactional public AuthSession refresh(String raw){
        if(raw==null||raw.isBlank())throw new AuthException("refresh token is required");Instant now=clock.instant();var old=users.findRefreshTokenByHash(hash(raw)).orElseThrow(()->new AuthException("invalid refresh token"));
        if(!old.activeAt(now)){users.revokeRefreshFamily(old.familyId(),now);throw new AuthException("refresh token is expired or revoked");}
        var account=users.findById(old.userId()).filter(a->a.status()==UserStatus.ACTIVE).orElseThrow(()->new AuthException("account is unavailable"));
        return rotatedSession(account,old.familyId(),old.tokenId(),now);
    }
    @Override @Transactional public void logout(AuthenticatedUser actor){Instant now=clock.instant();users.revokeAllRefreshTokens(actor.userId(),now);users.incrementTokenVersion(actor.userId(),now);}
    @Override @Transactional public void deactivate(AuthenticatedUser actor){Instant now=clock.instant();users.updateStatus(actor.userId(),UserStatus.DISABLED,now);users.revokeAllRefreshTokens(actor.userId(),now);users.incrementTokenVersion(actor.userId(),now);}
    @Override public UserView me(AuthenticatedUser actor){var a=users.findById(actor.userId()).orElseThrow(()->new AuthException("account not found"));return view(a);}
    private AuthSession session(UserAccount account){return rotatedSession(account,UUID.randomUUID().toString(),null,clock.instant());}
    private AuthSession rotatedSession(UserAccount account,String family,String replaced,Instant now){
        String raw=UUID.randomUUID()+UUID.randomUUID().toString().replace("-","");String id=UUID.randomUUID().toString();Instant expiry=now.plus(refreshTtl);
        users.saveRefreshToken(new RefreshTokenRecord(id,account.userId(),family,hash(raw),expiry,null,null,now));if(replaced!=null)users.revokeRefreshToken(replaced,id,now);
        String sessionId=id;Instant accessExpiry=now.plus(accessTtl);return new AuthSession(account.userId(),tokens.issue(account,sessionId,accessExpiry),raw,accessExpiry,view(account));
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String normalize(String value){if(value==null)return "";String out=value.trim().toLowerCase(Locale.ROOT);if(!out.matches("[a-z0-9_.-]{3,64}"))throw new AuthException("username must be 3-64 lowercase letters, numbers, dot, dash or underscore");return out;}
    private static void validatePassword(String value){if(value==null||value.length()<10||value.length()>128)throw new AuthException("password must be 10-128 characters");}
    private static String displayName(String value,String fallback){String out=value==null?"":value.trim();return out.isBlank()?fallback:out.length()>80?out.substring(0,80):out;}
    private static UserView view(UserAccount account){return new UserView(account.userId(),account.normalizedUsername(),account.displayName(),account.roles());}
}
