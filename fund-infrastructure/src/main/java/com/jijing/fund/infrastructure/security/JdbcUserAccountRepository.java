package com.jijing.fund.infrastructure.security;

import com.jijing.fund.domain.identity.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcUserAccountRepository implements UserAccountRepository {
    private final JdbcTemplate jdbc;
    public JdbcUserAccountRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public Optional<UserAccount> findByUsername(String username){var rows=jdbc.query("SELECT * FROM user_account WHERE normalized_username=?",this::account,username);return rows.stream().findFirst();}
    @Override public Optional<UserAccount> findById(UserId id){var rows=jdbc.query("SELECT * FROM user_account WHERE user_id=?",this::account,id.value());return rows.stream().findFirst();}
    @Override public void save(UserAccount a){
        jdbc.update("INSERT INTO user_account(user_id,normalized_username,display_name,password_hash,status,token_version,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",a.userId().value(),a.normalizedUsername(),a.displayName(),a.passwordHash(),a.status().name(),a.tokenVersion(),0,ts(a.createdAt()),ts(a.updatedAt()));
        for(UserRole r:a.roles())jdbc.update("INSERT INTO user_role(user_id,role_code) VALUES(?,?)",a.userId().value(),r.name());
    }
    @Override public void saveRefreshToken(RefreshTokenRecord t){jdbc.update("INSERT INTO refresh_token(token_id,user_id,family_id,token_hash,expires_at,created_at) VALUES(?,?,?,?,?,?)",t.tokenId(),t.userId().value(),t.familyId(),t.tokenHash(),ts(t.expiresAt()),ts(t.createdAt()));}
    @Override public Optional<RefreshTokenRecord> findRefreshTokenByHash(String hash){var rows=jdbc.query("SELECT * FROM refresh_token WHERE token_hash=?",this::refresh,hash);return rows.stream().findFirst();}
    @Override public void revokeRefreshFamily(String family,Instant at){jdbc.update("UPDATE refresh_token SET revoked_at=COALESCE(revoked_at,?) WHERE family_id=?",ts(at),family);}
    @Override public void revokeRefreshToken(String id,String next,Instant at){jdbc.update("UPDATE refresh_token SET revoked_at=?,replaced_by_token_id=? WHERE token_id=? AND revoked_at IS NULL",ts(at),next,id);}
    @Override public void incrementTokenVersion(UserId userId,Instant at){jdbc.update("UPDATE user_account SET token_version=token_version+1,updated_at=? WHERE user_id=?",ts(at),userId.value());}
    @Override public void revokeAllRefreshTokens(UserId userId,Instant at){jdbc.update("UPDATE refresh_token SET revoked_at=COALESCE(revoked_at,?) WHERE user_id=?",ts(at),userId.value());}
    @Override public void updateStatus(UserId userId,UserStatus status,Instant at){jdbc.update("UPDATE user_account SET status=?,updated_at=? WHERE user_id=?",status.name(),ts(at),userId.value());}
    private UserAccount account(java.sql.ResultSet r,int ignored)throws java.sql.SQLException{
        String id=r.getString("user_id");Set<UserRole> roles=new HashSet<>(jdbc.query("SELECT role_code FROM user_role WHERE user_id=?",(rs,n)->UserRole.valueOf(rs.getString(1)),id));
        return new UserAccount(new UserId(id),r.getString("normalized_username"),r.getString("display_name"),r.getString("password_hash"),UserStatus.valueOf(r.getString("status")),r.getInt("token_version"),roles,instant(r.getTimestamp("created_at")),instant(r.getTimestamp("updated_at")));
    }
    private RefreshTokenRecord refresh(java.sql.ResultSet r,int ignored)throws java.sql.SQLException{return new RefreshTokenRecord(r.getString("token_id"),new UserId(r.getString("user_id")),r.getString("family_id"),r.getString("token_hash"),instant(r.getTimestamp("expires_at")),instant(r.getTimestamp("revoked_at")),r.getString("replaced_by_token_id"),instant(r.getTimestamp("created_at")));}
    private static Timestamp ts(Instant value){return Timestamp.from(value);}
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
}
