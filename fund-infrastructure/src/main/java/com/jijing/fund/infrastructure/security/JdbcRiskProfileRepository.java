package com.jijing.fund.infrastructure.security;

import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.risk.RiskLevel;
import com.jijing.fund.domain.risk.RiskProfile;
import com.jijing.fund.domain.risk.RiskProfileRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 风险测评结果。同一用户可有多条，查询只取确认时间最新的一条。
 * 没有超时、空载荷特殊处理或重复提交去重；主键冲突由数据库抛出。
 */
public class JdbcRiskProfileRepository implements RiskProfileRepository {
    private final JdbcTemplate jdbc;

    /** 不在构造时查询。 */
    public JdbcRiskProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 没有记录时为空。 */
    @Override
    public Optional<RiskProfile> findLatestByOwner(UserId owner) {
        return jdbc.query(
                "SELECT * FROM risk_profile WHERE owner_user_id=? ORDER BY confirmed_at DESC LIMIT 1",
                this::map, owner.value()).stream().findFirst();
    }

    /** 追加一条测评，不更新旧记录。 */
    @Override
    public void save(RiskProfile profile) {
        jdbc.update("""
                INSERT INTO risk_profile(profile_id,owner_user_id,questionnaire_version,answers_hash,score,risk_level,confirmed_at,created_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, profile.profileId(), profile.ownerUserId().value(), profile.questionnaireVersion(),
                profile.answersHash(), profile.score(), profile.level().name(), ts(profile.confirmedAt()),
                ts(profile.createdAt()));
    }

    /** 一行测评。等级名必须能对上枚举，否则查询失败。 */
    private RiskProfile map(ResultSet row, int ignored) throws SQLException {
        return new RiskProfile(row.getString("profile_id"), new UserId(row.getString("owner_user_id")),
                row.getString("questionnaire_version"), row.getString("answers_hash"), row.getInt("score"),
                RiskLevel.valueOf(row.getString("risk_level")), instant(row.getTimestamp("confirmed_at")),
                instant(row.getTimestamp("created_at")));
    }

    /** JDBC 时间戳。 */
    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    /** 列值为空时由 JDBC 抛错，本方法不把 null 收成缺省时刻。 */
    private static Instant instant(Timestamp value) {
        return value.toInstant();
    }
}
