package com.jijing.fund.infrastructure.security;

import com.jijing.fund.domain.identity.RefreshTokenRecord;
import com.jijing.fund.domain.identity.UserAccount;
import com.jijing.fund.domain.identity.UserAccountRepository;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.domain.identity.UserStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 用户、角色和刷新令牌。用户名查询使用已规范化的用户名，本类不再变换大小写。
 * 刷新令牌家族吊销和单枚吊销都把已有吊销时间留住。没有 HTTP 超时；重复插入由数据库约束抛出。
 */
public class JdbcUserAccountRepository implements UserAccountRepository {
    private final JdbcTemplate jdbc;

    /** 不在构造时建连接以外的查询。 */
    public JdbcUserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 用户名未注册时为空。角色在映射时另查。 */
    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query("SELECT * FROM user_account WHERE normalized_username=?", this::account, username)
                .stream().findFirst();
    }

    /** 主键不存在时为空。 */
    @Override
    public Optional<UserAccount> findById(UserId id) {
        return jdbc.query("SELECT * FROM user_account WHERE user_id=?", this::account, id.value()).stream()
                .findFirst();
    }

    /** 账户行的乐观版本从 0 开始，角色逐条插入。 */
    @Override
    public void save(UserAccount account) {
        jdbc.update("""
                INSERT INTO user_account(user_id,normalized_username,display_name,password_hash,status,token_version,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, account.userId().value(), account.normalizedUsername(), account.displayName(),
                account.passwordHash(), account.status().name(), account.tokenVersion(), 0, ts(account.createdAt()),
                ts(account.updatedAt()));
        for (UserRole role : account.roles()) {
            jdbc.update("INSERT INTO user_role(user_id,role_code) VALUES(?,?)", account.userId().value(), role.name());
        }
    }

    /** 追加一枚刷新令牌。相同令牌主键由数据库拒绝。 */
    @Override
    public void saveRefreshToken(RefreshTokenRecord token) {
        jdbc.update("""
                INSERT INTO refresh_token(token_id,user_id,family_id,token_hash,expires_at,created_at)
                VALUES(?,?,?,?,?,?)
                """, token.tokenId(), token.userId().value(), token.familyId(), token.tokenHash(),
                ts(token.expiresAt()), ts(token.createdAt()));
    }

    /** 按哈希查找。哈希应是不可逆摘要，不是原始刷新串。 */
    @Override
    public Optional<RefreshTokenRecord> findRefreshTokenByHash(String hash) {
        return jdbc.query("SELECT * FROM refresh_token WHERE token_hash=?", this::refresh, hash).stream().findFirst();
    }

    /** 家族内已吊销的令牌保持原吊销时间。 */
    @Override
    public void revokeRefreshFamily(String family, Instant at) {
        jdbc.update("UPDATE refresh_token SET revoked_at=COALESCE(revoked_at,?) WHERE family_id=?", ts(at), family);
    }

    /** 只吊销尚未吊销的那一枚，并记下替换后的令牌。 */
    @Override
    public void revokeRefreshToken(String id, String next, Instant at) {
        jdbc.update("""
                UPDATE refresh_token SET revoked_at=?,replaced_by_token_id=?
                WHERE token_id=? AND revoked_at IS NULL
                """, ts(at), next, id);
    }

    /** 令牌版本加一后，旧访问令牌即使签名有效也不应再被接受。 */
    @Override
    public void incrementTokenVersion(UserId userId, Instant at) {
        jdbc.update("UPDATE user_account SET token_version=token_version+1,updated_at=? WHERE user_id=?",
                ts(at), userId.value());
    }

    /** 该用户全部刷新令牌标为吊销，已吊销的不改时间。 */
    @Override
    public void revokeAllRefreshTokens(UserId userId, Instant at) {
        jdbc.update("UPDATE refresh_token SET revoked_at=COALESCE(revoked_at,?) WHERE user_id=?",
                ts(at), userId.value());
    }

    /** 只改状态和时间，不碰令牌版本。 */
    @Override
    public void updateStatus(UserId userId, UserStatus status, Instant at) {
        jdbc.update("UPDATE user_account SET status=?,updated_at=? WHERE user_id=?",
                status.name(), ts(at), userId.value());
    }

    /** 账户行加上角色集合。角色码无法识别时查询失败。 */
    private UserAccount account(ResultSet row, int ignored) throws SQLException {
        String id = row.getString("user_id");
        Set<UserRole> roles = new HashSet<>(jdbc.query("SELECT role_code FROM user_role WHERE user_id=?",
                (rs, n) -> UserRole.valueOf(rs.getString(1)), id));
        return new UserAccount(new UserId(id), row.getString("normalized_username"), row.getString("display_name"),
                row.getString("password_hash"), UserStatus.valueOf(row.getString("status")), row.getInt("token_version"),
                roles, instant(row.getTimestamp("created_at")), instant(row.getTimestamp("updated_at")));
    }

    /** 刷新令牌行。吊销时间和替换编号可以为空。 */
    private RefreshTokenRecord refresh(ResultSet row, int ignored) throws SQLException {
        return new RefreshTokenRecord(row.getString("token_id"), new UserId(row.getString("user_id")),
                row.getString("family_id"), row.getString("token_hash"), instant(row.getTimestamp("expires_at")),
                instant(row.getTimestamp("revoked_at")), row.getString("replaced_by_token_id"),
                instant(row.getTimestamp("created_at")));
    }

    /** JDBC 时间戳。 */
    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    /** 可空时间列。 */
    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
