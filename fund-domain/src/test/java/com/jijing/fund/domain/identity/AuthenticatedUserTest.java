package com.jijing.fund.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证 {@link AuthenticatedUser} 对角色集合的规范化和角色判断在异常输入下的行为。 */
class AuthenticatedUserTest {
    /** 角色集合为 null 时视为无角色，任何角色判断都返回 false。 */
    @Test
    void nullRolesBecomeEmpty() {
        var user = new AuthenticatedUser(UserId.random(), null, "session");

        assertThat(user.roles()).isEmpty();
        assertThat(user.hasRole(UserRole.ADMIN)).isFalse();
    }

    /** 构造后修改原集合不影响对象，且对象暴露的角色集合不可修改。 */
    @Test
    void rolesAreDefensivelyCopied() {
        Set<UserRole> roles = new HashSet<>(Set.of(UserRole.USER));
        var user = new AuthenticatedUser(UserId.random(), roles, "session");
        roles.add(UserRole.ADMIN);

        assertThat(user.hasRole(UserRole.ADMIN)).isFalse();
        assertThatThrownBy(() -> user.roles().add(UserRole.ADMIN)).isInstanceOf(UnsupportedOperationException.class);
    }

    /** 角色集合中含 null 元素时构造失败并抛出 NullPointerException。 */
    @Test
    void rejectsNullRoleElement() {
        Set<UserRole> roles = new HashSet<>(Arrays.asList(UserRole.USER, null));

        assertThatThrownBy(() -> new AuthenticatedUser(UserId.random(), roles, "session"))
                .isInstanceOf(NullPointerException.class);
    }

    /** 用 null 查询角色时抛出 NullPointerException 而不是返回 false，调用方需自行避免传入 null。 */
    @Test
    void hasRoleRejectsNull() {
        var user = new AuthenticatedUser(UserId.random(), Set.of(UserRole.USER), "session");

        assertThatThrownBy(() -> user.hasRole(null)).isInstanceOf(NullPointerException.class);
    }
}
