package com.jijing.fund.application.auth;

import com.jijing.fund.domain.identity.AuthenticatedUser;

/**
 * 注册、登录、刷新、登出和账户停用的应用入口。
 * 身份不成立、口令不符、刷新令牌失效或用户名冲突时抛出认证异常，不返回部分会话。
 */
public interface AuthUseCase {

    /**
     * 创建普通用户并立即签发会话。
     * 用户名不符合允许字符或长度、口令长度不在六到一百二十八之间时抛出认证异常；规范化后的用户名已被占用时同样抛出认证异常且不覆盖原账户。
     *
     * @param command 注册所需的用户名、显示名和口令
     * @return 新账户的访问令牌、刷新令牌和用户视图
     */
    AuthSession register(RegisterCommand command);

    /**
     * 用用户名和口令换取新会话。
     * 用户名无法规范化、账户不存在、账户不是启用状态或口令不匹配时抛出认证异常，且不说明具体是哪一种，用户名格式不合法除外。
     *
     * @param command 登录所需的用户名和口令
     * @return 新的访问令牌、刷新令牌和用户视图
     */
    AuthSession login(LoginCommand command);

    /**
     * 轮换刷新令牌并签发新的访问令牌。
     * 刷新令牌缺失、无法识别、已过期或已撤销时抛出认证异常；过期或撤销还会作废同一令牌族。账户已停用或不存在时抛出认证异常且不签发新令牌。
     *
     * @param rawRefreshToken 调用方持有的刷新令牌原文
     * @return 轮换后的会话；旧刷新令牌会被替换
     */
    AuthSession refresh(String rawRefreshToken);

    /**
     * 撤销该用户的全部刷新令牌并提升令牌版本，使已发出的访问令牌失效。
     * 不检查账户是否仍存在；调用方身份为空时在读取用户标识处失败。
     *
     * @param actor 已解析的当前用户
     */
    void logout(AuthenticatedUser actor);

    /**
     * 读取当前用户的公开资料。
     * 仓储中找不到该用户时抛出认证异常。
     *
     * @param actor 已解析的当前用户
     * @return 用户标识、用户名、显示名和角色
     */
    UserView me(AuthenticatedUser actor);

    /**
     * 停用当前账户，并撤销其全部刷新令牌、提升令牌版本。
     * 不检查账户是否仍存在；调用方身份为空时在读取用户标识处失败。
     *
     * @param actor 已解析的当前用户
     */
    void deactivate(AuthenticatedUser actor);
}
