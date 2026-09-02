package com.jijing.fund.infrastructure.security;

import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.risk.*;
import java.sql.Timestamp;import java.time.Instant;import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcRiskProfileRepository implements RiskProfileRepository {
    private final JdbcTemplate jdbc;
    public JdbcRiskProfileRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public Optional<RiskProfile> findLatestByOwner(UserId owner){return jdbc.query("SELECT * FROM risk_profile WHERE owner_user_id=? ORDER BY confirmed_at DESC LIMIT 1",this::map,owner.value()).stream().findFirst();}
    @Override public void save(RiskProfile p){jdbc.update("INSERT INTO risk_profile(profile_id,owner_user_id,questionnaire_version,answers_hash,score,risk_level,confirmed_at,created_at) VALUES(?,?,?,?,?,?,?,?)",p.profileId(),p.ownerUserId().value(),p.questionnaireVersion(),p.answersHash(),p.score(),p.level().name(),ts(p.confirmedAt()),ts(p.createdAt()));}
    private RiskProfile map(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new RiskProfile(r.getString("profile_id"),new UserId(r.getString("owner_user_id")),r.getString("questionnaire_version"),r.getString("answers_hash"),r.getInt("score"),RiskLevel.valueOf(r.getString("risk_level")),instant(r.getTimestamp("confirmed_at")),instant(r.getTimestamp("created_at")));}
    private static Timestamp ts(Instant v){return Timestamp.from(v);}private static Instant instant(Timestamp v){return v.toInstant();}
}
