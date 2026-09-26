package com.jijing.fund.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证 {@link UserAccount} 对登录名的必填校验和角色集合的规范化。 */
class UserAccountTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    /** 用给定登录名和角色构造一个正常状态的账户，其余字段取合法默认值。 */
    private static UserAccount account(String username, Set<UserRole> roles) {
        return new UserAccount(UserId.random(), username, "Alice", "hash", UserStatus.ACTIVE, 0, roles, NOW, NOW);
    }

    /** 登录名为 null、空串或纯空白时抛出 IllegalArgumentException。 */
    @Test
    void rejectsMissingUsername() {
        assertThatThrownBy(() -> account(null, Set.of())).isInstanceOf(IllegalArgumentException.class).hasMessage("username is required");
        assertThatThrownBy(() -> account("", Set.of())).isInstanceOf(IllegalArgumentException.class).hasMessage("username is required");
        assertThatThrownBy(() -> account(" \t", Set.of())).isInstanceOf(IllegalArgumentException.class).hasMessage("username is required");
    }

    /** 角色为 null 时视为空集合；非 null 时做不可变拷贝，之后修改原集合不影响账户。 */
    @Test
    void normalizesRoles() {
        assertThat(account("alice", null).roles()).isEmpty();

        Set<UserRole> roles = new HashSet<>(Set.of(UserRole.USER));
        var account = account("alice", roles);
        roles.add(UserRole.ADMIN);

        assertThat(account.roles()).containsExactly(UserRole.USER);
        assertThatThrownBy(() -> account.roles().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    /** 角色集合中含 null 元素时抛出 NullPointerException。 */
    @Test
    void rejectsNullRoleElement() {
        assertThatThrownBy(() -> account("alice", new HashSet<>(Arrays.asList(UserRole.USER, null))))
                .isInstanceOf(NullPointerException.class);
    }
}
