package com.jijing.fund.domain.identity;

import java.time.Instant;
import java.util.Optional;

/**
 * 用户账户与刷新令牌的持久化端口，负责账户的查找与创建、刷新令牌的保存与撤销，以及令牌版本和账户状态的更新。
 * 接口不校验参数，null 参数的处理由实现决定；更新类方法在目标不存在时通常静默不生效。
 */
public interface UserAccountRepository {
    /** 按已规范化的登录名查找账户，调用方需先完成大小写和空白规范化；不存在时返回空。 */
    Optional<UserAccount> findByUsername(String normalizedUsername);

    /** 按用户标识查找账户（含角色），不存在时返回空。 */
    Optional<UserAccount> findById(UserId userId);

    /** 新建账户及其角色；登录名或用户标识重复时由实现抛出存储层异常，而不是覆盖已有账户。 */
    void save(UserAccount account);

    /** 保存一条新的刷新令牌记录；令牌标识或哈希重复时由实现抛出存储层异常。 */
    void saveRefreshToken(RefreshTokenRecord token);

    /** 按令牌哈希查找刷新令牌记录，包括已撤销和已过期的记录，是否可用需再调用 {@link RefreshTokenRecord#activeAt}。 */
    Optional<RefreshTokenRecord> findRefreshTokenByHash(String hash);

    /** 撤销同一令牌家族下的全部刷新令牌，用于检测到令牌重放时整链作废；已撤销的记录应保留原撤销时间，可重复调用。 */
    void revokeRefreshFamily(String familyId, Instant revokedAt);

    /** 撤销单个刷新令牌并记录替代它的新令牌标识；令牌已被撤销时不应再次修改，保证轮换只生效一次。 */
    void revokeRefreshToken(String tokenId, String replacementTokenId, Instant revokedAt);

    /** 令牌版本加一，使该用户此前签发的访问令牌全部失效；用户不存在时不生效。 */
    void incrementTokenVersion(UserId userId, Instant updatedAt);

    /** 撤销该用户名下的所有刷新令牌（例如登出全部设备或停用账户）；已撤销的记录保留原撤销时间，可重复调用。 */
    void revokeAllRefreshTokens(UserId userId, Instant revokedAt);

    /** 更新账户状态（例如停用）；用户不存在时不生效。 */
    void updateStatus(UserId userId, UserStatus status, Instant updatedAt);
}
